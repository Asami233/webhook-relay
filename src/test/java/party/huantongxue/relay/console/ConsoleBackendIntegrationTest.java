package party.huantongxue.relay.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import party.huantongxue.relay.IntegrationTest;
import party.huantongxue.relay.delivery.ClaimedDelivery;
import party.huantongxue.relay.delivery.DeliveryAttempt;
import party.huantongxue.relay.delivery.DeliveryStore;
import party.huantongxue.relay.event.EventService;

/** D6 控制台用到的后端：概览统计、一键演示、静态托管与安全响应头。 */
@IntegrationTest
class ConsoleBackendIntegrationTest {

    private static final String ADMIN = "Bearer test-admin-token-0123456789";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EventService events;
    @Autowired
    DeliveryStore store;

    private String apiKey;
    private long tenantId;

    @BeforeEach
    void tenant() throws Exception {
        // 共享测试库：其他用例留下的未完成任务不应被本用例手动抢到
        jdbc.update("UPDATE delivery SET status = 'DEAD' WHERE status IN ('PENDING', 'IN_FLIGHT')");
        String body = mvc.perform(post("/api/admin/tenants").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"c-" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(body, "$.apiKey");
        tenantId = ((Number) JsonPath.read(body, "$.tenant.id")).longValue();
    }

    @Test
    void overviewCountsStatusesAndBucketsAttemptsPerMinute() throws Exception {
        mvc.perform(post("/api/v1/endpoints").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/hook\",\"eventTypes\":[\"*\"]}"))
                .andExpect(status().isCreated());
        events.ingest(tenantId, "s-1", "order.created", "{}");
        events.ingest(tenantId, "s-2", "order.created", "{}");
        // 手动完成其中一条，产生一次成功尝试
        ClaimedDelivery d = store.claim(1, Duration.ofSeconds(30)).getFirst();
        DeliveryAttempt a = new DeliveryAttempt();
        a.setDeliveryId(d.getId());
        a.setAttemptNo(d.getAttempts());
        a.setStartedAt(Instant.now());
        a.setDurationMs(5);
        a.setStatusCode(200);
        store.succeeded(d, a);

        mvc.perform(get("/api/v1/stats").header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").value(2))
                .andExpect(jsonPath("$.deliveries.SUCCEEDED").value(1))
                .andExpect(jsonPath("$.deliveries.PENDING").value(1))
                .andExpect(jsonPath("$.deliveries.DEAD").value(0))
                .andExpect(jsonPath("$.attemptsPerMinute.length()").value(60))
                .andExpect(jsonPath("$.attemptsPerMinute[59].ok").value(1))
                .andExpect(jsonPath("$.attemptsPerMinute[59].failed").value(0));
    }

    @Test
    void demoCreatesSinkAndEndpointThenWritesEventsAndRetiresPreviousRun() throws Exception {
        String first = mvc.perform(post("/api/v1/demo").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":20,\"failureRate\":0,\"latencyMs\":0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.events").value(20))
                .andReturn().getResponse().getContentAsString();
        String firstType = JsonPath.read(first, "$.eventType");
        long firstEndpoint = ((Number) JsonPath.read(first, "$.endpointId")).longValue();
        String firstSink = JsonPath.read(first, "$.sinkId");

        await().atMost(Duration.ofSeconds(10)).until(() -> countEvents(firstType) == 20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE endpoint_id = ?", Integer.class,
                firstEndpoint)).isEqualTo(20);

        mvc.perform(post("/api/v1/demo").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":5}"))
                .andExpect(status().isAccepted());
        // 上一次演示：端点停用、模拟接收方删除
        assertThat(jdbc.queryForObject("SELECT enabled FROM endpoint WHERE id = ?", Boolean.class, firstEndpoint))
                .isFalse();
        mvc.perform(get("/api/v1/sinks/" + firstSink).header("Authorization", "Bearer " + apiKey))
                .andExpect(status().isNotFound());
        List<String> types = jdbc.queryForList("SELECT DISTINCT type FROM event WHERE tenant_id = ?", String.class,
                tenantId);
        assertThat(types).contains(firstType);
    }

    @Test
    void consoleIsServedUnderStrictCspAndRootRedirects() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/console/"));
        mvc.perform(get("/console/index.html"))
                .andExpect(header().string("Content-Security-Policy", ConsoleConfig.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private int countEvents(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE tenant_id = ? AND type = ?", Integer.class,
                tenantId, type);
    }
}
