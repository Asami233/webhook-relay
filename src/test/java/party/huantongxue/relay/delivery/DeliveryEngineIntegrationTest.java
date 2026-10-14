package party.huantongxue.relay.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import party.huantongxue.relay.IntegrationTest;
import party.huantongxue.relay.endpoint.EndpointService;
import party.huantongxue.relay.event.EventService;
import party.huantongxue.relay.security.Tokens;
import party.huantongxue.relay.tenant.Tenant;
import party.huantongxue.relay.tenant.TenantMapper;

/** D2 验收：多实例不重复投递；重试、死信、租约过期回收与 fencing、优雅停机。 */
@IntegrationTest
class DeliveryEngineIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(10);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantMapper tenants;
    @Autowired
    EndpointService endpoints;
    @Autowired
    EventService events;
    @Autowired
    DeliveryStore store;

    private final List<DeliveryWorker> workers = new ArrayList<>();
    private TestReceiver receiver;
    private long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        // 共享测试库：别的用例留下的待投递任务（指向 example.com）不应被这里的 worker 抢走
        jdbc.update("UPDATE delivery SET status = 'DEAD', last_error = 'neutralized by test' "
                + "WHERE status IN ('PENDING', 'IN_FLIGHT')");
        receiver = new TestReceiver();
        Tenant t = new Tenant();
        t.setName("delivery-" + UUID.randomUUID());
        t.setApiKeyHash(Tokens.sha256Hex(UUID.randomUUID().toString()));
        t.setApiKeyPrefix("rk_test");
        t.setIngestQps(1000);
        tenants.insert(t);
        tenantId = t.getId();
    }

    @AfterEach
    void tearDown() {
        workers.forEach(DeliveryWorker::stop);
        receiver.close();
    }

    @Test
    void concurrentClaimersNeverGetTheSameRow() throws Exception {
        endpoint(receiver.url(), 8);
        ingest(200);

        Map<Long, String> owner = new ConcurrentHashMap<>();
        Set<Long> duplicates = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String me = "claimer-" + i;
                futures.add(pool.submit(() -> {
                    List<ClaimedDelivery> batch;
                    while (!(batch = store.claim(7, LEASE)).isEmpty()) {
                        batch.forEach(d -> {
                            if (owner.putIfAbsent(d.getId(), me) != null) {
                                duplicates.add(d.getId());
                            }
                        });
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(duplicates).isEmpty();
        assertThat(owner).hasSize(200);
        assertThat(new HashSet<>(owner.values())).as("work spread across claimers").hasSizeGreaterThan(1);
        assertThat(countStatus("IN_FLIGHT")).isEqualTo(200);
    }

    @Test
    void twoWorkersDeliverEveryEventExactlyOnce() throws Exception {
        receiver.delay(Duration.ofMillis(10));
        endpoint(receiver.url(), 8);
        ingest(300);

        DeliveryWorker a = worker("A");
        DeliveryWorker b = worker("B");
        a.start();
        b.start();
        awaitStatus("SUCCEEDED", 300, Duration.ofSeconds(60));

        Map<Long, Long> perDelivery = receiver.received.stream()
                .collect(Collectors.groupingBy(TestReceiver.Received::deliveryId, Collectors.counting()));
        assertThat(perDelivery).hasSize(300);
        assertThat(perDelivery.values()).containsOnly(1L);
        assertThat(a.completedCount()).as("worker A did work").isPositive();
        assertThat(b.completedCount()).as("worker B did work").isPositive();
        assertThat(a.completedCount() + b.completedCount()).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt da JOIN delivery d ON d.id = da.delivery_id "
                + "WHERE d.tenant_id = ?", Integer.class, tenantId)).isEqualTo(300);
    }

    @Test
    void retryableFailuresBackOffThenSucceed() throws Exception {
        receiver.respond(arrival -> arrival < 3 ? 503 : 200);
        endpoint(receiver.url(), 8);
        ingest(1);
        worker("A").start();
        awaitStatus("SUCCEEDED", 1, Duration.ofSeconds(20));

        long deliveryId = onlyDeliveryId();
        assertThat(jdbc.queryForObject("SELECT attempts FROM delivery WHERE id = ?", Integer.class, deliveryId))
                .isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT status_code FROM delivery_attempt WHERE delivery_id = ? ORDER BY attempt_no",
                Integer.class, deliveryId)).containsExactly(503, 503, 200);
        assertThat(receiver.received).extracting(TestReceiver.Received::attempt).containsExactly(1, 2, 3);
        // started_at 以 UTC 写入，与数据库 NOW(3) 比较应在一分钟内（顺带验证时区处理）
        assertThat(jdbc.queryForObject("SELECT MAX(ABS(TIMESTAMPDIFF(SECOND, started_at, NOW(3)))) "
                + "FROM delivery_attempt WHERE delivery_id = ?", Integer.class, deliveryId)).isLessThan(60);
        String body = receiver.received.getFirst().body();
        assertThat(body).startsWith("{\"id\":").contains("\"type\":\"order.created\"").contains("\"data\":{\"n\": 0}");
    }

    @Test
    void nonRetryableResponseGoesStraightToDead() throws Exception {
        receiver.respond(arrival -> 400);
        endpoint(receiver.url(), 8);
        ingest(1);
        worker("A").start();
        awaitStatus("DEAD", 1, Duration.ofSeconds(20));

        Map<String, Object> row = jdbc.queryForMap("SELECT attempts, last_status_code, last_error FROM delivery "
                + "WHERE tenant_id = ?", tenantId);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_status_code")).isEqualTo(400);
        assertThat((String) row.get("last_error")).contains("non-retryable");
    }

    @Test
    void exhaustedRetriesGoToDead() throws Exception {
        receiver.respond(arrival -> 500);
        endpoint(receiver.url(), 3);
        ingest(1);
        worker("A").start();
        awaitStatus("DEAD", 1, Duration.ofSeconds(20));

        assertThat(jdbc.queryForObject("SELECT attempts FROM delivery WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(3);
        assertThat(receiver.received).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT last_error FROM delivery WHERE tenant_id = ?", String.class, tenantId))
                .startsWith("attempts exhausted");
    }

    @Test
    void connectionFailuresAreRecordedAndRetried() throws Exception {
        String closedPort;
        try (TestReceiver temporary = new TestReceiver()) {
            closedPort = temporary.url();
        }
        endpoint(closedPort, 2);
        ingest(1);
        worker("A").start();
        awaitStatus("DEAD", 1, Duration.ofSeconds(20));

        List<Map<String, Object>> attempts = jdbc.queryForList("SELECT status_code, error FROM delivery_attempt da "
                + "JOIN delivery d ON d.id = da.delivery_id WHERE d.tenant_id = ? ORDER BY attempt_no", tenantId);
        assertThat(attempts).hasSize(2);
        assertThat(attempts).allSatisfy(r -> {
            assertThat(r.get("status_code")).isNull();
            assertThat((String) r.get("error")).startsWith("connect failed: ").doesNotContain("null");
        });
    }

    @Test
    void disabledEndpointDeadLettersWithoutSending() throws Exception {
        long endpointId = endpoint(receiver.url(), 8);
        ingest(1);
        endpoints.update(tenantId, endpointId, null, null, null, false, null, null);
        worker("A").start();
        awaitStatus("DEAD", 1, Duration.ofSeconds(20));

        assertThat(receiver.received).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_error FROM delivery WHERE tenant_id = ?", String.class, tenantId))
                .isEqualTo("endpoint disabled");
    }

    @Test
    void expiredLeaseIsSweptAndStaleResultIsFencedOff() throws Exception {
        endpoint(receiver.url(), 8);
        ingest(1);

        ClaimedDelivery first = store.claim(10, LEASE).getFirst();
        assertThat(first.getAttempts()).isEqualTo(1);
        // 模拟实例在投递途中崩溃：租约到期
        jdbc.update("UPDATE delivery SET lease_until = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?", first.getId());
        assertThat(store.sweepExpiredLeases(100)).isEqualTo(1);
        assertThat(countStatus("PENDING")).isEqualTo(1);

        ClaimedDelivery second = store.claim(10, LEASE).getFirst();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getAttempts()).isEqualTo(2);

        // 旧实例“复活”后迟到的结果不能覆盖新一轮投递的状态
        assertThat(store.succeeded(first, attempt(first, 200))).isFalse();
        assertThat(countStatus("IN_FLIGHT")).isEqualTo(1);
        assertThat(store.succeeded(second, attempt(second, 200))).isTrue();
        assertThat(countStatus("SUCCEEDED")).isEqualTo(1);
        // 两次尝试都留档（审计上能看到旧实例确实发过一次）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt WHERE delivery_id = ?",
                Integer.class, first.getId())).isEqualTo(2);
    }

    @Test
    void gracefulStopWaitsForInFlightDeliveries() throws Exception {
        receiver.delay(Duration.ofMillis(1500));
        endpoint(receiver.url(), 8);
        ingest(5);
        DeliveryWorker a = worker("A");
        a.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> receiver.started.get() == 5);

        a.stop();

        assertThat(countStatus("SUCCEEDED")).isEqualTo(5);
        assertThat(countStatus("IN_FLIGHT")).isZero();
    }

    // ---- helpers ----

    private DeliveryWorker worker(String name) {
        DeliveryProperties props = new DeliveryProperties(true, Duration.ofMillis(50), 10, 50, 50,
                Duration.ofSeconds(1), Duration.ofSeconds(3), LEASE, Duration.ofMillis(50), Duration.ofMillis(200),
                Duration.ofHours(1), Duration.ofSeconds(10));
        DeliveryWorker w = new DeliveryWorker(name, store, new DeliverySender(props), props);
        workers.add(w);
        return w;
    }

    private long endpoint(String url, int maxAttempts) {
        return endpoints.create(tenantId, url, null, List.of("*"), maxAttempts, null).endpoint().getId();
    }

    private void ingest(int n) {
        for (int i = 0; i < n; i++) {
            events.ingest(tenantId, "k-" + i, "order.created", "{\"n\": " + i + "}");
        }
    }

    private void awaitStatus(String status, int expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100))
                .until(() -> countStatus(status) == expected);
    }

    private int countStatus(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE tenant_id = ? AND status = ?",
                Integer.class, tenantId, status);
    }

    private long onlyDeliveryId() {
        return jdbc.queryForObject("SELECT id FROM delivery WHERE tenant_id = ?", Long.class, tenantId);
    }

    private static DeliveryAttempt attempt(ClaimedDelivery d, int status) {
        DeliveryAttempt a = new DeliveryAttempt();
        a.setDeliveryId(d.getId());
        a.setAttemptNo(d.getAttempts());
        a.setStartedAt(Instant.now());
        a.setDurationMs(1);
        a.setStatusCode(status);
        return a;
    }
}
