package party.huantongxue.relay.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import party.huantongxue.relay.metrics.RelayMetrics;
import party.huantongxue.relay.ratelimit.TokenBucket;
import party.huantongxue.relay.metrics.RelayMetrics.Deferred;
import party.huantongxue.relay.metrics.RelayMetrics.DeliveryResult;
import party.huantongxue.relay.ssrf.SsrfGuard;

/**
 * 一个实例的投递循环：一个平台线程负责抢任务和定期清扫，每条投递在独立虚拟线程里执行。
 *
 * <p>并发用信号量限制而不是线程池大小：虚拟线程很便宜，真正稀缺的是下游端点和本机的连接/内存，
 * 所以限制“在途投递数”（全局 + 单端点）才是对的。
 *
 * <p>多实例安全靠数据库：抢任务用 SKIP LOCKED，回写结果用 attempts 做 fencing，实例之间不需要互相通信。
 */
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int SWEEP_BATCH = 500;
    private static final Duration RELEASE_DELAY = Duration.ofSeconds(1);
    /**
     * 被投递侧限流时，等待不超过这个时长就在虚拟线程里原地等（睡眠几乎零成本），超过才退回队列。
     * D5 压测：deliverQps=20、50 次/秒时，原先每条投递平均被退回约 15 次（每次两次数据库写）。
     */
    private static final long MAX_INLINE_RATE_WAIT_MS = 1000;

    private final String name;
    private final DeliveryStore store;
    private final DeliverySender sender;
    private final SsrfGuard ssrf;
    private final EndpointGuard guard;
    private final RelayMetrics metrics;
    private final DeliveryProperties props;

    private final Semaphore inFlight;
    /** 单端点并发许可。端点数量有限（每租户几个到几十个），常驻 Map 的开销可以接受。 */
    private final Map<Long, Semaphore> perEndpoint = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    /** 本实例接收到新事件时被唤醒，不必等满一个轮询周期（多实例时其他实例仍靠轮询）。 */
    private final Semaphore wakeups = new Semaphore(0);
    private final AtomicLong completed = new AtomicLong();
    private volatile boolean running;
    private Thread loop;
    private long lastSweepNanos;

    public DeliveryWorker(String name, DeliveryStore store, DeliverySender sender, SsrfGuard ssrf,
                          EndpointGuard guard, RelayMetrics metrics, DeliveryProperties props) {
        this.name = name;
        this.store = store;
        this.sender = sender;
        this.ssrf = ssrf;
        this.guard = guard;
        this.metrics = metrics;
        this.props = props;
        this.inFlight = new Semaphore(props.maxConcurrency());
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Thread.ofPlatform().name("relay-claimer-" + name).start(this::runLoop);
        log.info("delivery worker {} started (batch={}, maxConcurrency={}, perEndpoint={})",
                name, props.batchSize(), props.maxConcurrency(), props.perEndpointConcurrency());
    }

    /**
     * 优雅停机：先停止抢新任务，再等在途投递完成。超时仍未完成的会被中断，
     * 它们的租约到期后由（任一实例的）清扫任务退回 PENDING，不会丢。
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopSignal.countDown();
        wakeups.release();
        try {
            loop.join(props.shutdownTimeout().toMillis());
            boolean drained = inFlight.tryAcquire(props.maxConcurrency(),
                    props.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (drained) {
                inFlight.release(props.maxConcurrency());
            } else {
                log.warn("delivery worker {}: in-flight deliveries did not finish within {}, interrupting; "
                        + "their leases will expire and be swept", name, props.shutdownTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
        log.info("delivery worker {} stopped after {} completed deliveries", name, completed.get());
    }

    public boolean isRunning() {
        return running;
    }

    /** 有新任务入库：空闲中的抢任务循环立即醒来。多次调用只累积一个唤醒。 */
    public void wakeup() {
        if (wakeups.availablePermits() == 0) {
            wakeups.release();
        }
    }

    /** 本实例已完成（写回了结果）的投递数，测试用来证明多实例都参与了工作。 */
    public long completedCount() {
        return completed.get();
    }

    private void runLoop() {
        while (running) {
            try {
                sweepIfDue();
                int dispatched = pollOnce();
                if (dispatched == 0 && wakeups.tryAcquire(props.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    wakeups.drainPermits();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 数据库短暂不可用等：记录后退避一个轮询周期再试，循环本身不能死
                log.error("delivery worker {} poll failed", name, e);
                try {
                    stopSignal.await(props.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 抢一批并分发；返回分发条数。只在空闲许可范围内抢，避免抢到了却没并发额度去发、白白占着租约。 */
    int pollOnce() throws InterruptedException {
        int free = inFlight.availablePermits();
        if (free == 0) {
            // 满载：等任一在途投递结束再抢
            inFlight.acquire();
            inFlight.release();
            return 0;
        }
        List<ClaimedDelivery> claimed = store.claim(Math.min(props.batchSize(), free), props.lease());
        for (ClaimedDelivery d : claimed) {
            inFlight.acquire();
            executor.execute(() -> {
                try {
                    deliver(d);
                } finally {
                    inFlight.release();
                }
            });
        }
        return claimed.size();
    }

    void sweepIfDue() {
        long now = System.nanoTime();
        if (now - lastSweepNanos < props.sweepInterval().toNanos()) {
            return;
        }
        lastSweepNanos = now;
        int swept = store.sweepExpiredLeases(SWEEP_BATCH);
        if (swept > 0) {
            log.warn("delivery worker {} returned {} expired leases to PENDING", name, swept);
        }
    }

    void deliver(ClaimedDelivery d) {
        MDC.put("delivery_id", Long.toString(d.getId()));
        MDC.put("event_id", Long.toString(d.getEventId()));
        try {
            if (d.isEndpointDeleted() || !d.isEndpointEnabled()) {
                finish(d, store.dead(d, null, d.isEndpointDeleted() ? "endpoint deleted" : "endpoint disabled"),
                        DeliveryResult.DEAD);
                return;
            }
            if (d.attemptsThisRound() > d.getMaxAttempts()) {
                // 只会出现在“租约过期被清扫”之后：那次尝试可能已送达，已计入次数
                finish(d, store.dead(d, null, "attempts exhausted (lease expired on last attempt)"),
                        DeliveryResult.DEAD);
                return;
            }
            // 发送前再做一次 SSRF 检查：登记之后域名可能被改指向内网（DNS rebinding）
            SsrfGuard.Decision target = ssrf.check(d.getUrl());
            if (!target.allowed()) {
                if (target.retryable()) {
                    handle(d, DeliverySender.Result.notSent(target.reason()));
                } else {
                    finish(d, store.dead(d, null, "blocked by SSRF policy: " + target.reason()), DeliveryResult.DEAD);
                }
                return;
            }
            // 排队等端点许可与原地等限流共用一个截止时间，保证总等待 + 一次请求仍在租约之内
            long deadline = System.nanoTime() + props.endpointPermitWait().toNanos();
            Semaphore endpointPermits = perEndpoint.computeIfAbsent(d.getEndpointId(),
                    id -> new Semaphore(props.perEndpointConcurrency()));
            if (!endpointPermits.tryAcquire(props.endpointPermitWait().toMillis(), TimeUnit.MILLISECONDS)) {
                // 该端点太慢、排队太久：不发，退回 PENDING 并撤销计数，免得租约在途中过期
                store.releaseUnattempted(d, RELEASE_DELAY);
                metrics.deferred(Deferred.ENDPOINT_BUSY);
                return;
            }
            try {
                // 熔断与限流在拿到端点许可之后再判断：排队期间熔断可能已经打开，必须看最新状态
                Duration open = guard.openFor(d.getEndpointId());
                if (open != null) {
                    store.releaseUnattempted(d, open);
                    metrics.deferred(Deferred.CIRCUIT_OPEN);
                    return;
                }
                long budgetMs = Math.min(MAX_INLINE_RATE_WAIT_MS, (deadline - System.nanoTime()) / 1_000_000);
                TokenBucket.Result slot = guard.reserveDelivery(d.getEndpointId(), d.getDeliverQps(),
                        Duration.ofMillis(Math.max(0, budgetMs)));
                if (!slot.allowed()) {
                    store.releaseUnattempted(d, slot.delay());
                    metrics.deferred(Deferred.RATE_LIMITED);
                    return;
                }
                if (!slot.delay().isZero()) {
                    Thread.sleep(slot.delay()); // 已预约令牌：睡到轮到自己，不再重抢
                }
                handle(d, sender.send(d));
            } finally {
                endpointPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // 写回失败（如数据库抖动）：不再重试写，租约到期后清扫会让它重新投递（至少一次）
            log.error("delivery {} failed to record outcome", d.getId(), e);
        } finally {
            MDC.remove("delivery_id");
            MDC.remove("event_id");
        }
    }

    private void handle(ClaimedDelivery d, DeliverySender.Result r) {
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setDeliveryId(d.getId());
        attempt.setAttemptNo(d.getAttempts());
        attempt.setStartedAt(r.startedAt());
        attempt.setDurationMs(r.durationMs());
        attempt.setStatusCode(r.statusCode());
        attempt.setError(DeliveryStore.truncate(r.error(), 512));
        attempt.setResponseSnippet(r.snippet());

        RetryPolicy.Outcome outcome = RetryPolicy.classify(r.statusCode());
        metrics.attempt(Duration.ofMillis(r.durationMs()), outcome == RetryPolicy.Outcome.SUCCESS);
        switch (outcome) {
            case SUCCESS -> {
                guard.recordSuccess(d.getEndpointId());
                if (finish(d, store.succeeded(d, attempt), DeliveryResult.SUCCEEDED)) {
                    metrics.endToEnd(Duration.between(d.getEventCreatedAt(), Instant.now()));
                }
            }
            case PERMANENT -> finish(d, store.dead(d, attempt, "non-retryable HTTP " + r.statusCode()),
                    DeliveryResult.DEAD);
            case RETRY -> {
                guard.recordFailure(d.getEndpointId(), props.breakerThreshold(), props.breakerCooldown());
                String reason = r.error() != null ? r.error() : "HTTP " + r.statusCode();
                if (d.attemptsThisRound() >= d.getMaxAttempts()) {
                    finish(d, store.dead(d, attempt, "attempts exhausted: " + reason), DeliveryResult.DEAD);
                } else {
                    attempt.setError(DeliveryStore.truncate(reason, 512));
                    Duration delay = RetryPolicy.backoff(d.attemptsThisRound(), props.backoffBase(),
                            props.backoffMax(), r.retryAfter(), ThreadLocalRandom.current());
                    finish(d, store.retry(d, attempt, delay), DeliveryResult.RETRIED);
                }
            }
        }
    }

    /** @return 是否成功写回（false 表示租约已丢，结果只作为尝试记录保留） */
    private boolean finish(ClaimedDelivery d, boolean updated, DeliveryResult result) {
        if (updated) {
            completed.incrementAndGet();
            metrics.delivery(result);
        } else {
            log.warn("delivery {} attempt {} lost its lease; result kept as attempt record only",
                    d.getId(), d.getAttempts());
        }
        return updated;
    }
}
