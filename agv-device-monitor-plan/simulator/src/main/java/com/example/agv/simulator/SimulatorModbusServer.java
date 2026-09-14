package com.example.agv.simulator;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.digitalpetri.modbus.exceptions.UnknownUnitIdException;
import com.digitalpetri.modbus.server.ModbusRequestContext.ModbusTcpRequestContext;
import com.digitalpetri.modbus.server.ModbusTcpServer;
import com.digitalpetri.modbus.server.RawModbusTcpRequest;
import com.digitalpetri.modbus.server.RawModbusTcpResponse;
import com.digitalpetri.modbus.server.RawModbusTcpServices;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Protocol boundary：T04/T05/T12，库处理TCP拆包、MBAP头与响应关联，本类实现功能码03的寄存器切片。
 * request.unitId选择SimulatedFleet中的车辆，不使用HTTP路径或deviceCode；共享监听端口不能因单车掉线而关闭。
 * <p>Separate clocks and I/O：独立ticker每秒更新内存，requests处理协议，Netty负责通道事件。
 * 请求处理拿到Unit快照副本后即释放设备锁，再编码和发送；不能等客户端收完网络响应才放锁。
 * 只读范围由功能码限制：不会实现写寄存器、任务下发或真实车辆控制。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SimulatorModbusServer implements RawModbusTcpServices, AutoCloseable, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(SimulatorModbusServer.class);
    private final SimulatedFleet fleet;
    private final String address;
    private final int configuredPort;
    private final CompletableFuture<Integer> boundPort = new CompletableFuture<>();
    private NioEventLoopGroup eventLoops;
    private ExecutorService requests;
    private ScheduledExecutorService ticker;
    private ModbusTcpServer server;
    private volatile Channel listener;
    private volatile ScheduledFuture<?> updates;
    private volatile boolean stopping;

    public SimulatorModbusServer(SimulatedFleet fleet,
            @Value("${simulator.modbus.bind-address}") String address,
            @Value("${simulator.modbus.port}") int port) {
        if (address == null || address.isBlank() || port < 0 || port > 65535) {
            throw new IllegalArgumentException("A bind address and port 0..65535 are required");
        }
        this.fleet = fleet;
        this.address = address;
        this.configuredPort = port;
    }

    @PostConstruct
    public void start() throws Exception {
        // Own all resources: 不使用库的全局线程池，关闭应用或启动失败时释放本实例的资源。
        eventLoops = new NioEventLoopGroup(1);
        requests = Executors.newSingleThreadExecutor(r -> new Thread(r, "agv-modbus-requests"));
        ticker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "agv-simulator-tick"));
        try {
            var transport = NettyTcpServerTransport.create(config -> config
                    .setBindAddress(address).setPort(configuredPort)
                    .setEventLoopGroup(eventLoops).setExecutor(requests)
                    .setBootstrapCustomizer(bootstrap -> bootstrap.handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext context) throws Exception {
                            // Discover an ephemeral port: 测试直接绑定0，避免先找空闲端口再绑定的竞争。
                            listener = context.channel();
                            boundPort.complete(((InetSocketAddress) context.channel().localAddress()).getPort());
                            super.channelActive(context);
                        }
                    })));
            server = ModbusTcpServer.create(transport, this);
            server.start();
            log.info("Simulator Modbus listening on {}:{}", address, port());
            updates = ticker.scheduleAtFixedRate(() -> {
                try {
                    fleet.tick();
                } catch (RuntimeException failure) {
                    // Report a stopped updater: 定时任务失败必须可见，不能停止更新后假装正常。
                    log.error("Simulator register update failed", failure);
                    throw failure;
                }
            }, 1, 1, TimeUnit.SECONDS);
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public Health health() {
        // Check real lifecycle: 只有监听活跃且更新任务仍在运行时，模拟器才就绪。
        Channel current = listener;
        ScheduledFuture<?> task = updates;
        return !stopping && current != null && current.isActive() && task != null && !task.isDone()
                ? Health.up().build() : Health.down().build();
    }

    public int port() throws Exception {
        return boundPort.get(5, TimeUnit.SECONDS);
    }

    /**
     * Serve one read：先选车并取得一致副本，再校验PDU结构、功能码、起始地址和数量。
     * SILENT直接停止该请求的响应流程，不sleep共享请求线程、不返回全零、不关闭整个服务。
     * 错误区分：异常码1为不支持功能，2为地址越界，3为请求长度/数量非法；正常数据仍按大端uint16编码。
     */
    @Override
    public Optional<RawModbusTcpResponse> handleRawTcpRequest(ModbusTcpRequestContext context,
                                                            RawModbusTcpRequest request) throws Exception {
        int id = request.unitId();
        if (id < 1 || id > 3) {
            throw new UnknownUnitIdException(id);
        }
        var snapshot = fleet.snapshot(id);
        if (snapshot.mode() == SimulatedFleet.Scenario.SILENT) {
            // Drop without waiting: 2.1.6 用此异常明确跳过响应；不能 sleep，也不能返回全零假数据。
            // No Optional.empty here: 在此库中 empty 意味着继续默认处理，并不表示丢弃请求。
            throw new UnknownUnitIdException(id);
        }
        byte[] pdu = request.pdu();
        if (pdu.length == 0) {
            // Reject missing function code: 无功能码时无法形成合法异常响应，由传输层记录并关闭此连接。
            throw new IllegalArgumentException("Missing Modbus function code");
        }
        int function = Byte.toUnsignedInt(pdu[0]);
        if (function != 3) {
            return exception(function, 1);
        }
        if (pdu.length != 5) {
            return exception(function, 3);
        }
        var input = ByteBuffer.wrap(pdu);
        int start = Short.toUnsignedInt(input.getShort(1));
        int count = Short.toUnsignedInt(input.getShort(3));
        if (count < 1 || count > 125) {
            return exception(function, 3);
        }
        // Zero-based range：合法块的索引为0～7，读取范围是[start,start+count)，不是文档中的40001编号。
        if (start + count > 8) {
            return exception(function, 2);
        }
        // Serialize a detached block: 设备锁已经释放，仅对本次副本编码；保持高字节在前。
        var output = ByteBuffer.allocate(2 + count * 2).put((byte) 3).put((byte) (count * 2));
        for (int i = start; i < start + count; i++) {
            output.putShort((short) snapshot.registers()[i]);
        }
        return Optional.of(new RawModbusTcpResponse(output.array()));
    }

    // Modbus exception response：响应功能码设置最高位，再附异常码；这不是有效业务寄存器数据。
    private static Optional<RawModbusTcpResponse> exception(int function, int code) {
        return Optional.of(new RawModbusTcpResponse(new byte[] {(byte) (function | 0x80), (byte) code}));
    }

    @PreDestroy
    @Override
    public void close() throws Exception {
        stopping = true;
        if (ticker != null) {
            ticker.shutdownNow();
        }
        try {
            if (server != null) {
                server.stop();
            }
        } finally {
            // Close even after failure: 监听或连接关闭失败，也必须回收线程；不关闭其他实例的资源。
            if (requests != null) {
                requests.shutdownNow();
            }
            if (eventLoops != null) {
                eventLoops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            }
            if (requests != null && !requests.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Modbus request executor did not stop");
            }
            if (ticker != null && !ticker.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Simulator ticker did not stop");
            }
        }
    }
}
