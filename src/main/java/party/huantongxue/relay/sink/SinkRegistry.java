package party.huantongxue.relay.sink;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.common.ApiException;

/**
 * 模拟接收方的内存注册表。只用于演示：单实例、重启即清空，数量有上限。
 *
 * <p>重复检测按 {@code X-Relay-Delivery-Id}：同一条投递被<b>成功</b>接收两次即为一次重复，
 * 用来直观展示“至少一次”语义（例如对方处理成功但响应超时，Relay 会再发一次）。
 */
@Component
public class SinkRegistry {

    /** 每个模拟接收方最多记住多少个成功投递 ID；超过后停止重复检测，避免无限增长。 */
    static final int MAX_TRACKED_DELIVERIES = 200_000;

    private final SinkProperties properties;
    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public SinkRegistry(SinkProperties properties) {
        this.properties = properties;
    }

    /**
     * @param failureRate   0–1，按此概率返回 failureStatus
     * @param failureStatus 失败时的状态码（默认 503）
     * @param latencyMs     固定延迟
     * @param jitterMs      额外随机延迟上限
     */
    public record Config(double failureRate, int failureStatus, int latencyMs, int jitterMs) {
    }

    public record Stats(long received, long succeeded, long failed, long uniqueDelivered, long duplicates,
                        boolean duplicateTrackingTruncated, Instant lastReceivedAt) {
    }

    public static final class Sink {
        final String id;
        final long tenantId;
        final String name;
        final Instant createdAt = Instant.now();
        volatile Config config;
        final AtomicLong received = new AtomicLong();
        final AtomicLong succeeded = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong duplicates = new AtomicLong();
        final Set<String> delivered = ConcurrentHashMap.newKeySet();
        volatile boolean truncated;
        volatile Instant lastReceivedAt;

        Sink(String id, long tenantId, String name, Config config) {
            this.id = id;
            this.tenantId = tenantId;
            this.name = name;
            this.config = config;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Config config() {
            return config;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Stats stats() {
            return new Stats(received.get(), succeeded.get(), failed.get(), delivered.size(), duplicates.get(),
                    truncated, lastReceivedAt);
        }

        /** 记录一次请求；返回本次是否应失败（由调用方据此返回状态码）。 */
        void record(String deliveryId, boolean fail) {
            received.incrementAndGet();
            lastReceivedAt = Instant.now();
            if (fail) {
                failed.incrementAndGet();
                return;
            }
            succeeded.incrementAndGet();
            if (deliveryId == null) {
                return;
            }
            if (delivered.size() >= MAX_TRACKED_DELIVERIES) {
                truncated = true;
                return;
            }
            if (!delivered.add(deliveryId)) {
                duplicates.incrementAndGet();
            }
        }
    }

    public synchronized Sink create(long tenantId, String name, Config config) {
        if (sinks.size() >= properties.maxSinks()) {
            throw new ApiException(HttpStatus.CONFLICT, "sink_limit", "too many sinks on this instance");
        }
        if (sinks.values().stream().filter(s -> s.tenantId == tenantId).count() >= properties.maxPerTenant()) {
            throw new ApiException(HttpStatus.CONFLICT, "sink_limit", "at most " + properties.maxPerTenant() + " sinks per tenant");
        }
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Sink sink = new Sink(id, tenantId, name, config);
        sinks.put(id, sink);
        return sink;
    }

    /** 公开接收入口用：只凭随机 ID 查找（128 位，不可猜）。 */
    public Sink find(String id) {
        return sinks.get(id);
    }

    /** 租户接口用：必须属于该租户，否则视为不存在。 */
    public Sink require(long tenantId, String id) {
        Sink sink = sinks.get(id);
        if (sink == null || sink.tenantId != tenantId) {
            throw ApiException.notFound("sink");
        }
        return sink;
    }

    public List<Sink> list(long tenantId) {
        return sinks.values().stream().filter(s -> s.tenantId == tenantId)
                .sorted((a, b) -> a.createdAt.compareTo(b.createdAt)).toList();
    }

    public void delete(long tenantId, String id) {
        require(tenantId, id);
        sinks.remove(id);
    }

    public String url(Sink sink) {
        return properties.receivePrefix() + sink.id;
    }
}
