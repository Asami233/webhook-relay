package party.huantongxue.relay.sink;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import party.huantongxue.relay.IntegrationTest;

/** 模拟接收方：按配置失败、统计重复投递、租户隔离。 */
@IntegrationTest
class SinkIntegrationTest {

    private static final String ADMIN = "Bearer test-admin-token-0123456789";

    @Autowired
    MockMvc mvc;

    @Test
    void failsByConfigurationAndCountsDuplicates() throws Exception {
        String key = tenant();
        String created = mvc.perform(post("/api/v1/sinks").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\",\"config\":{\"failureRate\":1.0,\"failureStatus\":502}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(startsWith("http://127.0.0.1:8080/sink/")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        receive(id, "1").andExpect(status().is(502));
        mvc.perform(put("/api/v1/sinks/" + id + "/config").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"failureRate\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.failureRate").value(0.0))
                .andExpect(jsonPath("$.config.failureStatus").value(502));
        receive(id, "1").andExpect(status().isOk());
        receive(id, "2").andExpect(status().isOk());
        receive(id, "1").andExpect(status().isOk()); // 同一投递第二次成功接收 = 重复

        mvc.perform(get("/api/v1/sinks/" + id).header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.stats.received").value(4))
                .andExpect(jsonPath("$.stats.failed").value(1))
                .andExpect(jsonPath("$.stats.succeeded").value(3))
                .andExpect(jsonPath("$.stats.uniqueDelivered").value(2))
                .andExpect(jsonPath("$.stats.duplicates").value(1));
    }

    @Test
    void sinksArePrivateToTheirTenant() throws Exception {
        String owner = tenant();
        String other = tenant();
        String created = mvc.perform(post("/api/v1/sinks").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"mine\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(get("/api/v1/sinks/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sinks").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/sink/unknown-id")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/sinks").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"config\":{\"failureRate\":1.5}}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions receive(String sinkId, String deliveryId) throws Exception {
        return mvc.perform(post("/sink/" + sinkId).header("X-Relay-Delivery-Id", deliveryId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private String tenant() throws Exception {
        String body = mvc.perform(post("/api/admin/tenants").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"s-" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.apiKey");
    }
}
