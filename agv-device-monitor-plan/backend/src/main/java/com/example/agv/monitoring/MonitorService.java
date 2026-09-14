package com.example.agv.monitoring;

import com.example.agv.DemoProperties;
import com.example.agv.api.ApiViews;
import com.example.agv.collector.DeviceClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/**
 * Monitoring workflow：T06～T10/T14/T16，连接采集、状态机、事务和已提交视图的主调用链。
 * <p>Read in this order：start → scan → collect → DeviceState → persist → refreshView → view。
 * 用户启停走patch，登记走create，告警确认走ack；它们不通过模拟器管理接口修改真实设备状态。
 * <p>Three boundaries：正常响应先形成通信事实；新业务值只在候选中计算；完整事务成功后才接受候选。
 * 实时页面从数据库已提交视图读取，不直接序列化工作线程中的候选对象。
 * <p>Lock scope：每设备锁串行化状态、启停和该设备短事务，锁外执行Modbus读取。
 * 数据库变慢仍会占用该设备锁及工作线程，因此另设SQL/I/O上限和降级指标，不能承诺完全无影响。
 * <p>Evidence：MonitorConcurrencyIT检查排队/在途启停，MonitoringPersistenceIT检查回滚与历史，
 * MonitoringApiIT检查接口/SSE，system-check.py验证真实数据库故障与重启。
 */
public final class MonitorService implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(MonitorService.class);
    private final MeterRegistry metrics;
    private final MonitorRepository repository;
    private final DeviceClient client;
    private final MonitorSettings settings;
    private final DemoProperties demo;
    private final Clock clock;
    private final LongSupplier nanos;
    private final boolean automatic;
    private final ConcurrentMap<Long,RuntimeDevice> devices=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    // Separate scheduled work：调度、健康扫描和数据库视图刷新各有执行器，网络等待交给有界workers。
    // No catch-up backlog：错过的采集轮次不无限追赶，监控应尽快获取当前状态而不是排队读取“过去”。
    private final ScheduledExecutorService scanner=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService viewRefresher=Executors.newSingleThreadScheduledExecutor();
    private final Object viewLock=new Object();
    private final Object streamLock=new Object();
    private volatile boolean initialized;
    private final AtomicBoolean started=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong skipped=new AtomicLong();
    private volatile boolean scanFailed;
    private volatile boolean viewFailed=true;
    // Publish whole views：volatile发布完整不可变引用；绝不分别发布设备列表和告警列表造成版本混搭。
    private volatile CommittedView committed=new CommittedView(List.of(),List.of());
    private final String epoch=UUID.randomUUID().toString();
    private final AtomicLong sequence=new AtomicLong();

    public MonitorService(MonitorRepository repository,DeviceClient client,MonitorSettings settings,
                          DemoProperties demo,Clock clock,LongSupplier nanos,boolean automatic) {
        this(repository,client,settings,demo,clock,nanos,automatic,new SimpleMeterRegistry());
    }

    public MonitorService(MonitorRepository repository,DeviceClient client,MonitorSettings settings,
                          DemoProperties demo,Clock clock,LongSupplier nanos,boolean automatic,MeterRegistry metrics) {
        this.metrics=metrics;
        this.repository=repository; this.client=client; this.settings=settings; this.demo=demo;
        this.clock=clock; this.nanos=nanos; this.automatic=automatic;
        workers=new ThreadPoolExecutor(settings.workers(),settings.workers(),0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()),new ThreadPoolExecutor.AbortPolicy());
        metrics.gauge("agv.poll.queue.size",workers,pool -> pool.getQueue().size());
        metrics.gauge("agv.poll.workers.active",workers,ThreadPoolExecutor::getActiveCount);
        metrics.gauge("agv.poll.inflight",devices,map -> map.values().stream().filter(device -> device.inFlight.get()).count());
    }

    /**
     * Load after initialization：T02/T16，ApplicationReadyEvent在迁移和ApplicationRunner种子初始化之后触发。
     * 从数据库加载设备身份和旧测量，重置本进程观察窗口；旧ONLINE不是重启后的在线证据。
     * 活动告警仍以数据库为准，不因运行态Map重新创建就重复插入告警。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false,true)) return;
        // Revalidate on restart: 旧值只供参考；不能把数据库的旧 ONLINE 直接公布为当前在线。
        for (Device device:repository.devices()) {
            RuntimeDevice runtime=new RuntimeDevice(device,repository.initialState(device,nanos.getAsLong()));
            devices.put(device.id(),runtime);
            persist(runtime,runtime.state.copy(),false,"RESTART");
        }
        refreshView();
        initialized=true;
        if (automatic) {
            viewRefresher.scheduleWithFixedDelay(this::refreshView,settings.sseInterval(),settings.sseInterval(),TimeUnit.MILLISECONDS);
            scanner.scheduleWithFixedDelay(this::scan,0,settings.interval(),TimeUnit.MILLISECONDS);
            watchdog.scheduleWithFixedDelay(this::watch,settings.watchdog(),settings.watchdog(),TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Schedule one attempt：每轮只为启用且没有在途任务的设备提交一次读取。
     * inFlight覆盖排队、网络、状态处理和持久化整个生命周期；队列拒绝也必须释放标记。
     * 设备繁忙与队列满分别计数，它们表示跳过轮次，不等同于设备离线或已丢失一条可补写的历史。
     */
    public void scan() {
        if (closed.get()) return;
        try {
            for (RuntimeDevice runtime:devices.values()) {
                Device captured=runtime.device;
                if (!captured.enabled()) continue;
                if (!runtime.inFlight.compareAndSet(false,true)) {
                    metrics.counter("agv.poll.skipped","reason","busy").increment();
                    continue;
                }
                try {
                    workers.execute(() -> collect(runtime,captured));
                } catch (RejectedExecutionException full) {
                    runtime.inFlight.set(false);
                    skipped.incrementAndGet();
                    metrics.counter("agv.poll.skipped","reason","queue").increment();
                    log.warn("Collection queue full; skipping device {}",captured.id());
                }
            }
            scanFailed=false;
        } catch (RuntimeException failure) {
            scanFailed=true;
            log.error("Collection scan failed",failure);
        }
    }

    /**
     * Run outside the scheduler：工作线程持有本轮捕获的配置版本，真正读取前、读取后都检查是否仍有效。
     * 例如停用后又启用，enabled可能再次为true，但旧configRevision的结果仍不能覆盖新观察窗口。
     */
    private void collect(RuntimeDevice runtime,Device captured) {
        try {
            // Check queued work: 排队期间已停用或改版的任务不能再开始网络读取。
            synchronized (runtime) {
                if (!current(runtime,captured)) return;
            }
            DeviceClient.ReadResult result;
            long readStarted=System.nanoTime();
            String outcome="ERROR";
            try {
                result=client.read(captured);
                outcome=result.kind().name();
                if (!result.normalResponse() || result.kind()==DeviceClient.ReadResult.Kind.INVALID)
                    metrics.counter("agv.poll.errors","kind",outcome).increment();
            } catch (InterruptedException interrupted) {
                outcome="INTERRUPTED";
                throw interrupted;
            } finally {
                // Fixed outcome tags: 记录真实整轮网络耗时，不使用设备别名制造无限指标标签。
                metrics.timer("agv.poll.duration","outcome",outcome).record(System.nanoTime()-readStarted,TimeUnit.NANOSECONDS);
            }
            synchronized (runtime) {
                // Fence late results: 启停/配置变更后，旧请求的结果不能覆盖新状态。
                if (!current(runtime,captured)) return;
                long now=nanos.getAsLong();
                Instant at=clock.instant();
                // Keep communication facts: 正常响应时间不依赖业务事务是否提交。
                if (result.normalResponse()) {
                    // Store facts before the transaction：写库失败不能抹掉“设备刚刚正常响应”这一事实。
                    // Do not confuse stale storage with frozen data：新心跳提交失败时，repeatedHeartbeat仍应为false。
                    runtime.responseNanos=now; runtime.responseAt=at;
                    runtime.repeatedHeartbeat=result.sample()!=null && !result.sample().hasNewHeartbeat(runtime.state.heartbeat);
                }
                else log.debug("Device {} read failed: {}",captured.id(),result.reason());
                // Work on a disposable copy：不能先改runtime.state再尝试SQL，否则回滚后内存与数据库矛盾。
                DeviceState next=runtime.candidate();
                boolean fresh=result.normalResponse() && next.response(result.sample(),now,at);
                next.watch(now,settings);
                persist(runtime,next,fresh,result.reason());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            runtime.failed=true;
            metrics.counter("agv.poll.errors","kind","ERROR").increment();
            log.error("Device {} collection failed",captured.id(),failure);
        } finally { runtime.inFlight.set(false); }
    }

    private boolean current(RuntimeDevice runtime,Device captured) {
        return !closed.get() && runtime.device.enabled() && runtime.device.configRevision()==captured.configRevision();
    }

    /**
     * Detect silence without waiting for a result：健康扫描独立计算离线/陈旧阈值，避免完全依赖读请求回调。
     * 只有状态变化或上次保存失败才尝试提交；重复扫描相同状态不重复制造离线事件。
     * 保存失败后重算候选使用已接受业务基准，不偷偷把失败的新电量补写为可信快照。
     */
    public void watch() {
        if (closed.get()) return;
        for (RuntimeDevice runtime:devices.values()) {
            try {
                synchronized (runtime) {
                    DeviceState next=runtime.candidate();
                    next.watch(nanos.getAsLong(),settings);
                    if (!next.connection.equals(runtime.state.connection) || !next.quality.equals(runtime.state.quality) || runtime.failed)
                        persist(runtime,next,false,"WATCHDOG");
                }
            } catch (RuntimeException failure) {
                // Isolate failures: 一台设备失败不能终止整个健康扫描定时任务。
                runtime.failed=true;
                log.error("Device {} watchdog failed",runtime.device.id(),failure);
            }
        }
    }

    /**
     * Commit before accepting：T07/T08，快照、应保存的历史、离散事件和告警构成一个原子业务结果。
     * repository返回才推进心跳/历史基准；失败保留旧基准并标记DEGRADED，不缓冲无界重试样本。
     * 相同失败心跳下次仍可重新尝试，因为它尚未被业务接受；错过的真实历史不能凭空还原。
     */
    private void persist(RuntimeDevice runtime,DeviceState next,boolean fresh,String reason) {
        long now=nanos.getAsLong();
        boolean saveHistory=fresh && next.historyDue(now,settings);
        try {
            repository.persist(runtime.device,next,fresh,saveHistory,UUID.randomUUID().toString(),clock.instant(),reason);
            // Accept after commit: 失败候选直接丢弃，心跳及历史基准仅在完整提交后推进。
            if (saveHistory) next.historyNanos=now;
            runtime.state=next;
            runtime.failed=false;
        } catch (RuntimeException failure) {
            runtime.failed=true;
            metrics.counter("agv.persistence.failures").increment();
            log.error("Device {} persistence failed; business candidate discarded",runtime.device.id(),failure);
        }
    }

    /**
     * Read a committed pair：T10，设备快照和活动告警在同一个数据库事务中读取，再一次替换缓存。
     * 第二个查询失败时也要保留完整旧缓存；数据库恢复后刷新成功只清除viewFailed，不掩盖设备保存失败。
     * viewLock仅约束视图刷新，浏览器慢读不会持有它，也不会持有设备锁。
     */
    @SuppressWarnings("unchecked")
    public void refreshView() {
        // Separate read lane: 只串行化视图刷新，不持有设备锁；提交后一次替换完整视图。
        synchronized (viewLock) {
            if (closed.get()) return;
            try {
                CommittedView next=repository.transaction(() -> new CommittedView(repository.snapshots().stream().map(ApiViews.Snapshot::from).toList(),
                        ((List<Map<String,Object>>)repository.alarms(null,null,"ACTIVE",1,100).get("items")).stream().map(ApiViews.Alarm::from).toList()));
                committed=next;
                viewFailed=false;
            } catch (RuntimeException failure) {
                viewFailed=true;
                metrics.counter("agv.view.failures").increment();
                log.error("Committed view refresh failed; retaining previous view",failure);
            }
        }
    }

    /**
     * Build a delivery envelope：streamEpoch区分进程，sequence表示推送生成顺序，不是设备心跳。
     * generatedAt是此帧生成时间，可能包着旧缓存；判断测量新旧要看lastFreshAt和collectorStatus。
     * 无需访问数据库即可输出降级帧，数据库不可用时也能向页面说明正在展示最后已提交数据。
     */
    public Map<String,Object> view() {
        synchronized (streamLock) {
            CommittedView view=committed;
            return Map.of("streamEpoch",epoch,"sequence",sequence.incrementAndGet(),"generatedAt",clock.instant(),
                    "collectorStatus",healthy() ? "RUNNING" : "DEGRADED","devices",view.devices(),"activeAlarms",view.alarms());
        }
    }

    private record CommittedView(List<ApiViews.Snapshot> devices,List<ApiViews.Alarm> alarms) {
        CommittedView { devices=List.copyOf(devices); alarms=List.copyOf(alarms); }
    }

    // Service health is not device health：车辆OFFLINE是被正常监测出的业务状态；服务处理失败才影响这里。
    public boolean healthy() { return initialized && !closed.get() && !scanFailed && !viewFailed && devices.values().stream().noneMatch(d -> d.failed); }
    public long skipped() { return skipped.get(); }

    /**
     * Serialize registration：T02/T09，单实例内串行检查容量，编码/连接元组唯一性最终由MySQL约束。
     * 台账与UNKNOWN快照同事务创建，允许列表在建连前限制目标；登记不代表已经连接或读到有效值。
     * 本方法锁住service实例不等于跨实例锁，不能据此宣称分布式容量控制。
     */
    public synchronized Map<String,Object> create(String code,String name,String host,int port,int unit,boolean enabled) {
        if (!demo.allowedHosts().contains(host) || !demo.allowedPorts().contains(port))
            throw new IllegalArgumentException("连接目标不在允许列表内");
        long id=repository.transaction(() -> {
            if (repository.count()>=demo.maxDevices()) throw new CapacityException();
            return repository.create(code,name,host,port,unit,enabled);
        });
        devices.put(id,new RuntimeDevice(repository.device(id),new DeviceState(nanos.getAsLong(),enabled,null)));
        return repository.deviceView(id);
    }

    /**
     * Update monitoring configuration：名称和启停可变，连接元组保持设备身份稳定。
     * 停用将活动告警SUPPRESSED并保留旧测量；重新启用重建计时，不表示设备已经恢复正常。
     * 数据库事务失败时不改变运行态配置；提交后再断开旧会话，在途结果还需通过revision检查。
     */
    public Map<String,Object> patch(long id,String name,Boolean enabled) {
        RuntimeDevice runtime=devices.get(id);
        if (runtime==null) throw new NoSuchElementException("设备不存在");
        synchronized (runtime) {
            Device current=runtime.device;
            String nextName=name==null ? current.name() : name;
            boolean nextEnabled=enabled==null ? current.enabled() : enabled;
            if (nextName.equals(current.name()) && nextEnabled==current.enabled()) return repository.deviceView(id);
            boolean toggled=nextEnabled!=current.enabled();
            DeviceState next=toggled ? runtime.state.restart(nanos.getAsLong(),nextEnabled) : runtime.state;
            // Update atomically: 停用台账、快照、活动告警一起提交；提交失败不修改本地启停状态。
            Device updated=repository.transaction(() -> {
                repository.patch(id,nextName,nextEnabled);
                Device changed=repository.device(id);
                if (toggled) repository.write(changed,next,false,false,null,clock.instant(),nextEnabled ? "ENABLED" : "DISABLED");
                return changed;
            });
            runtime.device=updated;
            runtime.state=next;
            if (toggled) {
                runtime.responseNanos=null;
                runtime.responseAt=null;
                runtime.repeatedHeartbeat=false;
                client.disconnect(id);
            }
            return repository.deviceView(id);
        }
    }

    public Map<String,Object> ack(long id) {
        return repository.transaction(() -> repository.ack(id));
    }

    // Stop producers before consumers：先停止新调度与在途工作，关闭真实连接后有界等待线程退出。
    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        scanner.shutdownNow(); watchdog.shutdownNow(); viewRefresher.shutdownNow(); workers.shutdownNow();
        client.close();
        for (ExecutorService executor:List.of(scanner,watchdog,viewRefresher,workers)) {
            try {
                if (!executor.awaitTermination(5,TimeUnit.SECONDS)) log.error("Monitoring executor did not terminate");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Per-device ownership：device为配置快照，state为已接受业务状态，response字段为独立通信事实。
     * candidate仅合并通信事实到业务副本，不从失败事务的样本重建新电量或心跳基准。
     */
    private static final class RuntimeDevice {
        volatile Device device;
        DeviceState state;
        Long responseNanos;
        Instant responseAt;
        boolean repeatedHeartbeat;
        final AtomicBoolean inFlight=new AtomicBoolean();
        volatile boolean failed;
        RuntimeDevice(Device device,DeviceState state) { this.device=device; this.state=state; }
        DeviceState candidate() {
            DeviceState next=state.copy();
            if (device.enabled() && responseNanos!=null) {
                next.connection="ONLINE";
                next.responseNanos=responseNanos;
                next.lastResponseAt=responseAt;
                next.repeatedHeartbeat=repeatedHeartbeat;
            }
            return next;
        }
    }
    public static final class CapacityException extends RuntimeException { }
}
