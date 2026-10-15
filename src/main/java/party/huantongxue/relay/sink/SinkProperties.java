package party.huantongxue.relay.sink;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled       是否开启内置模拟接收方
 * @param baseUrl       Relay 自己对投递引擎可达的地址；接收地址为 {@code <baseUrl>/sink/<id>}
 * @param maxSinks      全局最多同时存在的模拟接收方数量（内存保护）
 * @param maxPerTenant  每个租户最多几个
 */
@ConfigurationProperties("relay.sink")
public record SinkProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:8080") String baseUrl,
        @DefaultValue("200") int maxSinks,
        @DefaultValue("10") int maxPerTenant) {

    /** SSRF 放行的唯一前缀；未开启时为 null。 */
    public String receivePrefix() {
        if (!enabled || baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/sink/";
    }
}
