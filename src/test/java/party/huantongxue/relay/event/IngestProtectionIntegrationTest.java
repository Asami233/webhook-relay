package party.huantongxue.relay.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import party.huantongxue.relay.IntegrationTest;

/** D3 验收（接收侧）：超限 429、Redis 幂等快速路径、登记时拒绝内网地址。 */
@IntegrationTest
class IngestProtectionIntegrationTest {

    private static final String ADMIN = "Bearer test-admin-token-0123456789";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    @Test
    void ingestOverTenantQuotaGets429WithRetryAfter() throws Exception {
        Tenant t = tenant(5);
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 20; i++) {
            MvcResult r = postEvent(t.apiKey(), "k-" + i, "{}").andReturn();
            if (r.getResponse().getStatus() == 202) {
                accepted++;
            } else {
                assertThat(r.getResponse().getStatus()).isEqualTo(429);
                assertThat(Integer.parseInt(r.getResponse().getHeader("Retry-After"))).isPositive();
                assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"rate_limited\"");
                limited++;
            }
        }
        // 桶容量 5；20 个请求在远小于 1 秒内发完，最多再补一两个令牌
        assertThat(accepted).isBetween(5, 8);
        assertThat(limited).isEqualTo(20 - accepted);
        assertThat(countEvents(t.id())).isEqualTo(accepted);
    }

    @Test
    void limitsAreIsolatedPerTenant() throws Exception {
        Tenant a = tenant(2);
        Tenant b = tenant(2);
        for (int i = 0; i < 5; i++) {
            postEvent(a.apiKey(), "k-" + i, "{}");
        }
        postEvent(b.apiKey(), "k-0", "{}").andExpect(status().isAccepted());
    }

    @Test
    void replayIsServedFromRedisAfterFirstCommit() throws Exception {
        Tenant t = tenant(1000);
        String first = postEvent(t.apiKey(), "k-fast", "{\"a\":1}").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long eventId = ((Number) JsonPath.read(first, "$.eventId")).longValue();
        assertThat(redis.hasKey(IdempotencyCache.key(t.id(), "k-fast"))).isTrue();

        // 证明重放不经过数据库：删掉数据库里的事件后，重放仍返回首次结果
        jdbc.update("DELETE FROM delivery WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM event WHERE id = ?", eventId);
        postEvent(t.apiKey(), "k-fast", "{\"a\":1}")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.eventId").value(eventId));
        // 同键不同内容在缓存路径上同样被拒
        postEvent(t.apiKey(), "k-fast", "{\"a\":2}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void cacheMissFallsBackToDatabase() throws Exception {
        Tenant t = tenant(1000);
        String first = postEvent(t.apiKey(), "k-miss", "{}").andReturn().getResponse().getContentAsString();
        redis.delete(IdempotencyCache.key(t.id(), "k-miss"));
        postEvent(t.apiKey(), "k-miss", "{}")
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.eventId").value(((Number) JsonPath.read(first, "$.eventId")).longValue()));
        assertThat(countEvents(t.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/hook", "http://127.1/hook", "http://10.0.0.1/hook", "http://192.168.1.10/hook",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]/hook", "http://0.0.0.0/hook",
            "http://2130706433/hook"
    })
    void registeringInternalUrlsIsRejected(String url) throws Exception {
        Tenant t = tenant(1000);
        mvc.perform(post("/api/v1/endpoints").header("Authorization", "Bearer " + t.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"eventTypes\":[\"*\"]}"))
                .andExpect(status().isBadRequest())
                // 127.1 这类写法 java.net.URI 解析不出主机，语法校验阶段就以 invalid_url 拒绝；其余由 SSRF 检查拒绝
                .andExpect(jsonPath("$.code").value(oneOf("url_not_allowed", "invalid_url")));
    }

    private record Tenant(long id, String apiKey) {
    }

    private Tenant tenant(int ingestQps) throws Exception {
        String body = mvc.perform(post("/api/admin/tenants").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"p-" + UUID.randomUUID() + "\",\"ingestQps\":" + ingestQps + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Tenant(((Number) JsonPath.read(body, "$.tenant.id")).longValue(), JsonPath.read(body, "$.apiKey"));
    }

    private ResultActions postEvent(String apiKey, String key, String payload) throws Exception {
        return mvc.perform(post("/api/v1/events").header("Authorization", "Bearer " + apiKey)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"order.created\",\"payload\":" + payload + "}"));
    }

    private int countEvents(long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE tenant_id = ?", Integer.class, tenantId);
    }
}
