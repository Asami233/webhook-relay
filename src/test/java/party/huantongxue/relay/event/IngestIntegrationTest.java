package party.huantongxue.relay.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import party.huantongxue.relay.TestcontainersConfig;

/** D1 验收：发事件后库里有 event + delivery；幂等、鉴权、租户隔离、订阅过滤。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class IngestIntegrationTest {

    private static final String ADMIN = "Bearer test-admin-token-0123456789";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EventService eventService;

    private String apiKey;
    private long tenantId;

    @BeforeEach
    void newTenant() throws Exception {
        String body = mvc.perform(post("/api/admin/tenants").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"t-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        apiKey = JsonPath.read(body, "$.apiKey");
        tenantId = ((Number) JsonPath.read(body, "$.tenant.id")).longValue();
    }

    @Test
    void eventIsPersistedTogetherWithOneDeliveryPerMatchingEndpoint() throws Exception {
        long orders = createEndpoint("[\"order.*\"]");
        long all = createEndpoint("[\"*\"]");
        createEndpoint("[\"invoice.paid\"]");
        long disabled = createEndpoint("[\"*\"]");
        mvc.perform(asTenant(patch("/api/v1/endpoints/" + disabled))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        String body = postEvent("k-1", "order.created", "{\"orderId\":42}")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.deliveries").value(2))
                .andReturn().getResponse().getContentAsString();
        long eventId = ((Number) JsonPath.read(body, "$.eventId")).longValue();

        List<Long> targets = jdbc.queryForList(
                "SELECT endpoint_id FROM delivery WHERE event_id = ? AND status = 'PENDING' ORDER BY endpoint_id",
                Long.class, eventId);
        assertThat(targets).containsExactly(orders, all);

        mvc.perform(asTenant(get("/api/v1/events/" + eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.orderId").value(42))
                .andExpect(jsonPath("$.deliveries.length()").value(2));
    }

    @Test
    void sameKeySameBodyReplaysFirstResult() throws Exception {
        createEndpoint("[\"*\"]");
        String first = postEvent("k-dup", "order.created", "{\"a\":1}").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = postEvent("k-dup", "order.created", "{\"a\":1}").andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(countEvents()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsRejected() throws Exception {
        postEvent("k-x", "order.created", "{\"a\":1}").andExpect(status().isAccepted());
        postEvent("k-x", "order.created", "{\"a\":2}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void concurrentSameKeyCreatesExactlyOneEvent() throws Exception {
        createEndpoint("[\"*\"]");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EventService.IngestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return eventService.ingest(tenantId, "k-race", "order.created", "{\"n\":1}");
                }));
            }
            start.countDown();
            List<EventService.IngestResult> results = new ArrayList<>();
            for (Future<EventService.IngestResult> f : futures) {
                results.add(f.get());
            }
            assertThat(results).extracting(EventService.IngestResult::eventId).containsOnly(results.getFirst().eventId());
            assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        }
        assertThat(countEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(1);
    }

    @Test
    void requiresValidApiKeyAndIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events").header("Authorization", "Bearer rk_wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(asTenant(post("/api/v1/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"a.b\",\"payload\":{}}"))
                .andExpect(status().isBadRequest());
        postEvent("k", "order.*", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_event_type"));
        mvc.perform(post("/api/admin/tenants").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantsCannotSeeEachOther() throws Exception {
        long endpointId = createEndpoint("[\"*\"]");
        String eventBody = postEvent("k-iso", "a.b", "{}").andReturn().getResponse().getContentAsString();
        long eventId = ((Number) JsonPath.read(eventBody, "$.eventId")).longValue();

        String ownKey = apiKey;
        newTenant();
        assertThat(apiKey).isNotEqualTo(ownKey);
        mvc.perform(asTenant(get("/api/v1/endpoints/" + endpointId))).andExpect(status().isNotFound());
        mvc.perform(asTenant(get("/api/v1/events/" + eventId))).andExpect(status().isNotFound());
        // 同一个 Idempotency-Key 在不同租户下互不影响
        postEvent("k-iso", "a.b", "{}").andExpect(header().string("Idempotent-Replayed", "false"));
    }

    @Test
    void endpointRejectsNonHttpUrlAndNeverReturnsSecretOnRead() throws Exception {
        mvc.perform(asTenant(post("/api/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"file:///etc/passwd\",\"eventTypes\":[\"*\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
        long id = createEndpoint("[\"*\"]");
        mvc.perform(asTenant(get("/api/v1/endpoints/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist());
        byte[] stored = jdbc.queryForObject("SELECT secret_ciphertext FROM endpoint WHERE id = ?", byte[].class, id);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("whsec_");
    }

    private long createEndpoint(String eventTypesJson) throws Exception {
        String body = mvc.perform(asTenant(post("/api/v1/endpoints")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/hook\",\"eventTypes\":" + eventTypesJson + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value(startsWith("whsec_")))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.endpoint.id")).longValue();
    }

    private ResultActions postEvent(String key, String type, String payload)
            throws Exception {
        return mvc.perform(asTenant(post("/api/v1/events")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"payload\":" + payload + "}"));
    }

    private MockHttpServletRequestBuilder asTenant(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + apiKey);
    }

    private int countEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE tenant_id = ?", Integer.class, tenantId);
    }
}
