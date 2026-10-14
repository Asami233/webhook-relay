package party.huantongxue.relay.ssrf;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param allowedHosts 跳过检查的主机名（与 URL 中的字面主机名精确、忽略大小写匹配）。
 *                     只用于测试与本机演示；生产应为空。
 */
@ConfigurationProperties("relay.ssrf")
public record SsrfProperties(@DefaultValue List<String> allowedHosts) {
}
