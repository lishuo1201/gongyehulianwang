package com.example.agv.stream;

import com.example.agv.monitoring.MonitorService;
import io.micrometer.core.instrument.MeterRegistry;
import com.example.agv.monitoring.MonitorSettings;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deliver committed snapshots：T10/T17，SSE单向推送完整监控视图，修改操作仍走REST。
 * <p>Read the two paths：connect注册浏览器 → Subscriber入队/发送；publisher周期取service.view再广播。
 * view只读已提交缓存，发送不执行SQL、不持有采集设备锁；数据库降级时仍能发送旧视图和降级标记。
 * <p>Bound slow consumers：最多20个连接/发送线程，每连接最多2个待发快照，溢出丢弃较旧待发帧。
 * 这不是事件可靠投递通道：丢帧/重连依靠下一份全量覆盖，完整历史和告警记录应查REST/数据库。
 * Evidence：MonitoringApiIT验证20连接与超限；StreamBackpressureIT实际停止读取TCP响应体制造背压。
 */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="monitor.enabled",matchIfMissing=true)
public class StreamController implements WebMvcConfigurer {
    private static final String SUBSCRIBER=StreamController.class.getName()+".subscriber";
    private static final Logger log=LoggerFactory.getLogger(StreamController.class);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Subscriber> subscribers=ConcurrentHashMap.newKeySet();
    // Reserve capacity, do not queue connections：SynchronousQueue不积压发送任务，资源不足直接拒绝新连接。
    private final ThreadPoolExecutor senders=new ThreadPoolExecutor(0,20,60,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final ScheduledExecutorService publisher=Executors.newSingleThreadScheduledExecutor();
    private final MonitorService service;
    private final MeterRegistry metrics;
    private final MonitorSettings settings;

    public StreamController(MonitorService service,MonitorSettings settings,MeterRegistry metrics) {
        this.service=service; this.settings=settings; this.metrics=metrics;
        metrics.gauge("agv.sse.connections",subscribers,Set::size);
        metrics.gauge("agv.sse.pending",subscribers,all -> all.stream().mapToInt(subscriber -> subscriber.pending.size()).sum());
        metrics.gauge("agv.sse.senders.active",senders,ThreadPoolExecutor::getActiveCount);
        publisher.scheduleWithFixedDelay(() -> {
            try {
                if (!subscribers.isEmpty()) {
                    Map<String,Object> view=service.view();
                    subscribers.forEach(subscriber -> subscriber.offer(view));
                }
            } catch (RuntimeException failure) { log.error("SSE snapshot publication failed",failure); }
        },settings.sseInterval(),settings.sseInterval(),TimeUnit.MILLISECONDS);
    }

    /**
     * Wait for the HTTP binding：Controller返回emitter并不等于MVC已经把它连接到实际响应。
     * 过早send会进入SseEmitter内部earlySendAttempts，绕过我们自己的两帧队列限制。
     * 此回调在MVC启动异步处理后释放ready，让唯一发送线程开始实际输出；初始化超时则关闭该连接。
     */
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,HttpServletResponse response,Object handler) {
                // Start after MVC initialization: 不向SseEmitter尚未绑定HTTP的早期缓冲区无限写入。
                if (request.getAttribute(SUBSCRIBER) instanceof Subscriber subscriber) subscriber.ready.countDown();
            }
        }).addPathPatterns("/api/v1/stream");
    }

    /**
     * Register atomically：连接上限检查和登记由此实例锁保护，避免多个并发请求同时抢占最后一个槽。
     * 先登记关闭回调、缓存初始帧并预留发送任务，再将emitter交给MVC；不能在HTTP已开始输出后才报告超限。
     */
    @GetMapping(value="/api/v1/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public synchronized SseEmitter connect(HttpServletRequest request) {
        if (closed.get() || subscribers.size()>=20) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"实时连接已达上限");
        Subscriber subscriber=new Subscriber();
        subscribers.add(subscriber);
        subscriber.emitter.onCompletion(subscriber::close);
        subscriber.emitter.onTimeout(subscriber::close);
        subscriber.emitter.onError(error -> subscriber.close());
        subscriber.offer(service.view());
        try { subscriber.register(senders.submit(subscriber::send)); }
        catch (RejectedExecutionException full) {
            subscriber.close();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"实时发送资源繁忙");
        }
        request.setAttribute(SUBSCRIBER,subscriber);
        return subscriber.emitter;
    }

    private final class Subscriber {
        // Finite stream lifetime：异步请求约60秒结束，浏览器EventSource重连后获取完整视图，不回放旧帧。
        final SseEmitter emitter=new SseEmitter(60_000L);
        final CountDownLatch ready=new CountDownLatch(1);
        final ArrayBlockingQueue<Map<String,Object>> pending=new ArrayBlockingQueue<>(2);
        final AtomicBoolean closed=new AtomicBoolean();
        volatile Future<?> task;
        long lastSequence=-1;
        // Merge full snapshots：初始帧和周期帧可竞争入队，序号检查必须与入队在同一锁内完成。
        synchronized void offer(Map<String,Object> view) {
            long sequence=((Number)view.get("sequence")).longValue();
            // Keep frame order: 初始帧与周期帧竞争时拒绝旧序号，慢连接仍只保留两帧。
            if (closed.get() || sequence<=lastSequence) return;
            lastSequence=sequence;
            if (!pending.offer(view)) {
                pending.poll(); pending.offer(view);
                metrics.counter("agv.sse.frames.dropped").increment();
            }
        }
        synchronized void register(Future<?> submitted) {
            // Close race: 提交与关闭交错时，晚登记的任务也必须取消。
            if (closed.get()) submitted.cancel(true);
            else task=submitted;
        }
        // Single writer per response：只有该发送任务写emitter，避免多线程交叉输出一个HTTP响应体。
        void send() {
            long lastHeartbeat=System.nanoTime();
            try {
                if (!ready.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("SSE initialization timed out");
                while (!closed.get()) {
                    Map<String,Object> next=pending.poll(settings.sseHeartbeat(),TimeUnit.MILLISECONDS);
                    if (next!=null) emitter.send(SseEmitter.event().name("snapshot").data(next));
                    // Transport heartbeat：SSE注释保活服务于浏览器连接，与寄存器heartbeat和设备新鲜度无关。
                    if (System.nanoTime()-lastHeartbeat>=settings.sseHeartbeat()*1_000_000L) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastHeartbeat=System.nanoTime();
                    }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { metrics.counter("agv.sse.failures").increment(); log.debug("SSE connection ended",failure); }
            finally { close(); }
        }
        // Idempotent cleanup：断开、超时、发送失败和应用关闭均可能到达这里，只释放一次槽位与任务。
        synchronized void close() {
            if (!closed.compareAndSet(false,true)) return;
            subscribers.remove(this); pending.clear();
            metrics.counter("agv.sse.closed").increment();
            if (task!=null) task.cancel(true);
            emitter.complete();
        }
    }
    @PreDestroy public synchronized void close() {
        if (!closed.compareAndSet(false,true)) return;
        publisher.shutdownNow();
        subscribers.forEach(Subscriber::close);
        senders.shutdownNow();
        for (ExecutorService executor:new ExecutorService[]{publisher,senders}) {
            try {
                if (!executor.awaitTermination(2,TimeUnit.SECONDS)) log.error("SSE executor did not terminate");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
