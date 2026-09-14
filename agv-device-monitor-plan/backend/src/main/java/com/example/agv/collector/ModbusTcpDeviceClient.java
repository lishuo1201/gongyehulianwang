package com.example.agv.collector;

import com.digitalpetri.modbus.TimeoutScheduler;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import com.example.agv.monitoring.Device;
import com.example.agv.monitoring.MonitorSettings;
import com.example.agv.protocol.RegisterCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded protocol adapter：T05/T06，MonitorService调用read，本类完成真实TCP读并分类失败。
 * <p>Follow one read：查找本设备Session → 在整轮预算内建连 → 发送功能码03 → 验证响应 → 解码业务值。
 * 网络等待发生在采集工作线程，不进入数据库事务或设备状态锁；同设备不重叠由MonitorService保证。
 * <p>Connection vs identity：三台车可共享host/port，但Unit ID不同；这里为每个deviceId复用独立连接，
 * 停用2号不能关闭1/3号的会话。当前最多10条设备登记，无需为此规模再加连接池。
 * <p>Evidence：DeviceClientIT用真实TCP验证报文、超时、取消和后续重新读取，不用REST假数据证明接入。
 */
public final class ModbusTcpDeviceClient implements DeviceClient {
    private static final Logger log = LoggerFactory.getLogger(ModbusTcpDeviceClient.class);
    private final MonitorSettings settings;
    private final ConcurrentMap<Long, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> disconnecting = ConcurrentHashMap.newKeySet();
    private final NioEventLoopGroup loops = new NioEventLoopGroup(2);
    private final ExecutorService callbacks = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ModbusTcpDeviceClient(MonitorSettings settings) { this.settings = settings; }

    @Override
    public ReadResult read(Device device) throws InterruptedException {
        // One deadline per cycle：首次和重试共用1800ms默认预算，不给每次重试重新分配完整预算。
        // Monotonic time：用nanoTime计算剩余量，避免系统时钟校准使超时忽长忽短。
        long deadline = System.nanoTime() + settings.cycleBudget() * 1_000_000L;
        String failure = "TIMEOUT";
        Session session;
        synchronized (sessions) {
            if (closed.get()) return cancelled();
            session=sessions.computeIfAbsent(device.id(),id -> new Session(device));
        }
        try {
            for (int attempt=0; attempt<settings.attempts(); attempt++) {
                if (closed.get() || session.disposed.get()) return cancelled();
                if (remaining(deadline)<=0) break;
                try {
                    Session current=session;
                    if (!current.transport.isConnected())
                        await(current.transport::connect,deadline,settings.connectTimeout());
                    if (current.disposed.get() || closed.get()) return cancelled();
                    // Read holding registers：03为功能码，00 00为零基起始地址，00 08为寄存器数量。
                    // Target the real unit：库生成MBAP头并携带Unit ID；deviceCode不会写入这个请求。
                    byte[] pdu=await(() -> current.client.sendRawAsync(device.unitId(),new byte[]{3,0,0,0,8}),
                            deadline,settings.requestTimeout());
                    if (current.disposed.get() || closed.get()) return cancelled();
                    // Validate framing before values：响应PDU应为功能码1字节 + 字节数1字节 + 数据16字节。
                    // Do not patch partial blocks：长度或功能码错误不能靠补零、截掉数据“修复”成成功样本。
                    if (pdu.length!=18 || pdu[0]!=3 || Byte.toUnsignedInt(pdu[1])!=16) {
                        current.close();
                        return new ReadResult(ReadResult.Kind.PROTOCOL,null,"INVALID_PDU");
                    }
                    try {
                        return new ReadResult(ReadResult.Kind.VALID,RegisterCodec.decode(Arrays.copyOfRange(pdu,2,18)),"VALID_SAMPLE");
                    } catch (IllegalArgumentException invalid) {
                        // Preserve communication evidence：如电量101或运行/故障冲突，响应已到但整组业务样本无效。
                        // No partial trust：不只剔除坏电量并继续接受同组心跳，业务基准由状态机统一保留。
                        return new ReadResult(ReadResult.Kind.INVALID,null,"INVALID_SAMPLE");
                    }
                } catch (InterruptedException interrupted) {
                    session.close();
                    throw interrupted;
                } catch (ExecutionException | TimeoutException failureException) {
                    // Close before retry：迟到响应不能混入后续请求；确定性的协议错误直接结束本轮。
                    // One timeout is not offline：这里只报告读取失败，是否达到10秒离线阈值由DeviceState决定。
                    session.close();
                    if (session.mismatchedFrame.get()) return new ReadResult(ReadResult.Kind.PROTOCOL,null,"RESPONSE_MISMATCH");
                    Throwable cause=failureException;
                    while (cause.getCause()!=null) cause=cause.getCause();
                    if (cause instanceof ModbusResponseException)
                        return new ReadResult(ReadResult.Kind.PROTOCOL,null,"MODBUS_EXCEPTION");
                    failure=cause instanceof TimeoutException ? "TIMEOUT" : "CONNECTION_FAILED";
                    if (attempt+1>=settings.attempts()) break;
                    long delay=Math.min(remaining(deadline),settings.retryDelay()*1_000_000L);
                    if (delay<=0) break;
                    // Sleep in the worker：只占本次采集工作线程，不在Netty事件线程或模拟器共享处理器里等待。
                    TimeUnit.NANOSECONDS.sleep(delay);
                    synchronized (sessions) {
                        // Fence cancelled reads: 停用/关闭已移除旧会话时，本轮重试不能重新建立连接。
                        if (closed.get() || sessions.get(device.id())!=session) return cancelled();
                        session=new Session(device);
                        sessions.put(device.id(),session);
                    }
                } catch (RuntimeException failureException) {
                    session.close();
                    if (closed.get()) return cancelled();
                    throw failureException;
                }
            }
            return new ReadResult(ReadResult.Kind.COMMUNICATION,null,failure);
        } finally {
            // Remove only our session: 旧请求退出时不能关闭后来创建的新会话。
            if (session.disposed.get()) sessions.remove(device.id(),session);
        }
    }

    private static ReadResult cancelled() { return new ReadResult(ReadResult.Kind.COMMUNICATION,null,"CANCELLED"); }

    private static long remaining(long deadline) { return deadline - System.nanoTime(); }

    /**
     * Bound each wait：建连/读取各有阶段上限，但都受同一个整轮剩余时间约束，取两者较小值。
     * Supplier把真正发起操作推迟到预算检查后；超时抛回read，由那里关闭实际连接资源。
     */
    private static <T> T await(Supplier<CompletionStage<T>> operation, long deadline, int stageTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = Math.min(remaining(deadline), stageTimeout * 1_000_000L);
        if (nanos <= 0) throw new TimeoutException("Cycle deadline reached");
        // Start inside budget: 截止后不再发起建连或发送，启动后重新计算等待额度。
        CompletionStage<T> pending=operation.get();
        nanos=Math.min(remaining(deadline),stageTimeout*1_000_000L);
        if (nanos<=0) throw new TimeoutException("Cycle deadline reached");
        return pending.toCompletableFuture().get(nanos,TimeUnit.NANOSECONDS);
    }

    @Override
    public void disconnect(long id) {
        synchronized (sessions) {
            Session session=sessions.remove(id);
            if (session!=null) session.close();
        }
    }

    /**
     * Own a device connection：Session记录通道、关闭标记和响应来源错误，不存业务样本。
     * disposed解决停用/关闭与异步建连交错；sessions中的对象身份解决旧请求误删新连接的问题。
     */
    private final class Session {
        final AtomicBoolean disposed = new AtomicBoolean();
        final AtomicBoolean mismatchedFrame = new AtomicBoolean();
        final AtomicReference<Channel> channel = new AtomicReference<>();
        final NettyTcpClientTransport transport;
        final ModbusTcpClient client;

        Session(Device device) {
            transport = NettyTcpClientTransport.create(config -> config.setHostname(device.host())
                    .setPort(device.port()).setConnectTimeout(Duration.ofMillis(settings.connectTimeout()))
                    .setConnectPersistent(false).setReconnectLazy(true).setEventLoopGroup(loops)
                    .setExecutor(callbacks).setPipelineCustomizer(pipeline -> {
                        channel.set(pipeline.channel());
                        if (disposed.get() || closed.get()) pipeline.channel().close();
                        // Validate source before delivery: 库按事务号匹配，但2.1.6不验证Unit ID；必须在交给库前校验。
                        String codec=pipeline.context(com.digitalpetri.modbus.tcp.ModbusTcpCodec.class).name();
                        pipeline.addAfter(codec,"verify-unit",new ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext context,Object message) throws Exception {
                                if (message instanceof com.digitalpetri.modbus.ModbusTcpFrame frame
                                        && (frame.header().unitId()!=device.unitId() || frame.header().protocolId()!=0)) {
                                    mismatchedFrame.set(true);
                                    context.close();
                                    return;
                                }
                                super.channelRead(context,message);
                            }
                        });
                    }));
            client = ModbusTcpClient.create(transport, config -> config
                    .setRequestTimeout(Duration.ofMillis(settings.requestTimeout()))
                    .setTimeoutScheduler(TimeoutScheduler.create(callbacks,timer)));
        }

        void close() {
            synchronized (sessions) {
                if (!disposed.compareAndSet(false,true)) return;
                // Abort transport, not just Future: 关闭实际通道并停止状态机自动连接，不只取消等待。
                Channel active = channel.get();
                if (active != null) active.close();
                CompletableFuture<?> pending=transport.disconnect().toCompletableFuture();
                disconnecting.add(pending);
                pending.whenComplete((ignored,error) -> {
                    if (error!=null) log.warn("Modbus disconnect failed",error);
                    disconnecting.remove(pending);
                });
            }
        }
    }

    /**
     * Shutdown in dependency order：先停止接入新读取，再关闭会话，等待断连回调，最后回收Netty及执行器。
     * 若先停Netty，断连状态机仍尝试注册回调会报event executor terminated；Future取消不等于通道已关闭。
     */
    @Override
    public void close() {
        CompletableFuture<?> pending;
        synchronized (sessions) {
            if (!closed.compareAndSet(false,true)) return;
            sessions.keySet().forEach(this::disconnect);
            pending=CompletableFuture.allOf(disconnecting.toArray(CompletableFuture<?>[]::new));
        }
        // Drain disconnect actions first: 状态机仍需向Netty注册关闭回调，先结束断连再关闭事件线程。
        try { pending.get(2,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException failure) { log.warn("Modbus disconnect drain failed",failure); }
        loops.shutdownGracefully(0,2,TimeUnit.SECONDS).syncUninterruptibly();
        callbacks.shutdown();
        timer.shutdownNow();
        try {
            if (!callbacks.awaitTermination(2,TimeUnit.SECONDS)) callbacks.shutdownNow();
        } catch (InterruptedException interrupted) {
            callbacks.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
