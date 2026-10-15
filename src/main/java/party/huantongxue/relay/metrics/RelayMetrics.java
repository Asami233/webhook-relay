package party.huantongxue.relay.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.delivery.DeliveryStatus;

/**
 * 业务指标（Prometheus 名称见各字段注释）。标签只用低基数的结果类别，<b>不按租户/端点打标签</b>：
 * 端点数量无上限，会让时间序列数量爆炸；按端点的排查走数据库与控制台。
 */
@Component
public class RelayMetrics {

    public enum DeliveryResult { SUCCEEDED, RETRIED, DEAD }

    public enum Deferred { RATE_LIMITED, CIRCUIT_OPEN, ENDPOINT_BUSY }

    public enum IngestResult { ACCEPTED, REPLAYED, RATE_LIMITED }

    /** relay_delivery_total{result} */
    private final Map<DeliveryResult, Counter> deliveries = new EnumMap<>(DeliveryResult.class);
    /** relay_delivery_deferred_total{reason}：没发出去、退回队列的次数 */
    private final Map<Deferred, Counter> deferred = new EnumMap<>(Deferred.class);
    /** relay_ingest_total{result} */
    private final Map<IngestResult, Counter> ingest = new EnumMap<>(IngestResult.class);
    /** relay_delivery_latency_seconds：事件创建到成功送达（端到端，含排队与重试） */
    private final Timer endToEnd;
    /** relay_delivery_attempt_seconds{outcome}：单次 HTTP 尝试耗时 */
    private final Timer attemptOk;
    private final Timer attemptFailed;

    /** relay_backlog：已到期待抢的任务数；relay_oldest_pending_seconds：其中最老的等了多久 */
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestPendingMillis = new AtomicLong();
    /** relay_deliveries{status}：未结束状态的任务数 */
    private final Map<DeliveryStatus, AtomicLong> byStatus = new EnumMap<>(DeliveryStatus.class);

    public RelayMetrics(MeterRegistry registry) {
        for (DeliveryResult r : DeliveryResult.values()) {
            deliveries.put(r, Counter.builder("relay.delivery").description("Delivery attempts by outcome")
                    .tag("result", r.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        for (Deferred d : Deferred.values()) {
            deferred.put(d, Counter.builder("relay.delivery.deferred")
                    .description("Claimed deliveries returned to the queue without sending")
                    .tag("reason", d.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        for (IngestResult r : IngestResult.values()) {
            ingest.put(r, Counter.builder("relay.ingest").description("Ingest requests by outcome")
                    .tag("result", r.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        endToEnd = Timer.builder("relay.delivery.latency")
                .description("Event creation to successful delivery")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofHours(2))
                .register(registry);
        attemptOk = attemptTimer(registry, "success");
        attemptFailed = attemptTimer(registry, "failure");

        Gauge.builder("relay.backlog", backlog, AtomicLong::get)
                .description("Due PENDING deliveries not yet claimed").register(registry);
        Gauge.builder("relay.oldest.pending", oldestPendingMillis, v -> v.get() / 1000.0)
                .description("Age of the oldest due PENDING delivery").baseUnit("seconds").register(registry);
        for (DeliveryStatus s : new DeliveryStatus[] {DeliveryStatus.PENDING, DeliveryStatus.IN_FLIGHT, DeliveryStatus.DEAD}) {
            AtomicLong holder = new AtomicLong();
            byStatus.put(s, holder);
            Gauge.builder("relay.deliveries", holder, AtomicLong::get)
                    .description("Deliveries in non-terminal or dead state")
                    .tag("status", s.name().toLowerCase(Locale.ROOT)).register(registry);
        }
    }

    private static Timer attemptTimer(MeterRegistry registry, String outcome) {
        return Timer.builder("relay.delivery.attempt")
                .description("Duration of a single HTTP delivery attempt")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }

    public void delivery(DeliveryResult result) {
        deliveries.get(result).increment();
    }

    public void deferred(Deferred reason) {
        deferred.get(reason).increment();
    }

    public void ingest(IngestResult result) {
        ingest.get(result).increment();
    }

    public void attempt(Duration duration, boolean success) {
        (success ? attemptOk : attemptFailed).record(duration);
    }

    /** 端到端延迟。用应用时钟减数据库写入的 created_at：两者应在同一时间源（NTP），偏差会直接计入。 */
    public void endToEnd(Duration sinceCreated) {
        if (!sinceCreated.isNegative()) {
            endToEnd.record(sinceCreated);
        }
    }

    public void backlog(long due, double oldestDueSeconds) {
        backlog.set(due);
        oldestPendingMillis.set(Math.round(oldestDueSeconds * 1000));
    }

    public void statusCount(DeliveryStatus status, long n) {
        AtomicLong holder = byStatus.get(status);
        if (holder != null) {
            holder.set(n);
        }
    }
}
