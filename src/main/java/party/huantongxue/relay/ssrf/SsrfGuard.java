package party.huantongxue.relay.ssrf;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.sink.SinkProperties;

/**
 * SSRF 防护：端点 URL 由租户填写，不能让 Relay 替攻击者访问内网、本机或云元数据服务。
 *
 * <p>做法是<b>解析后按 IP 判断</b>，而不是按主机名字符串匹配：{@code 2130706433}、{@code 0x7f.1}、
 * 指向 127.0.0.1 的公网域名等写法都绕不过去。登记时检查一次，<b>每次投递前再检查</b>（DNS 可能在登记后被改指内网，
 * 即 DNS rebinding）；投递不跟随重定向，避免被 302 引到内网。
 *
 * <p>已知局限：检查与 HttpClient 实际建连之间仍会各自解析一次，攻击者可用极短 TTL 在两次解析间切换。
 * 彻底的做法是把校验过的 IP 固定给连接（需自建 TLS SNI 处理），或经由专用出口代理统一过滤（如 Smokescreen）。
 */
@Component
public class SsrfGuard {

    /** 便于测试注入假的 DNS 结果。 */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record Decision(boolean allowed, boolean retryable, String reason) {
        static Decision allow() {
            return new Decision(true, false, null);
        }

        static Decision block(String reason) {
            return new Decision(false, false, reason);
        }

        static Decision unresolvable(String reason) {
            return new Decision(false, true, reason);
        }
    }

    private static final Pattern SINK_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Set<String> allowedHosts;
    private final String sinkPrefix;
    private final Resolver resolver;

    @Autowired
    public SsrfGuard(SsrfProperties properties, SinkProperties sink) {
        this(properties, sink.receivePrefix(), InetAddress::getAllByName);
    }

    public SsrfGuard(SsrfProperties properties, Resolver resolver) {
        this(properties, null, resolver);
    }

    /**
     * @param sinkPrefix 内置模拟接收方的地址前缀（如 {@code http://127.0.0.1:8080/sink/}），为 null 表示未开启。
     *                   只放行“前缀 + 一个 ID”这一种形状，不是放行整个主机。
     */
    public SsrfGuard(SsrfProperties properties, String sinkPrefix, Resolver resolver) {
        this.allowedHosts = properties.allowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.sinkPrefix = sinkPrefix;
        this.resolver = resolver;
    }

    public Decision check(String url) {
        if (isSinkUrl(url)) {
            return Decision.allow();
        }
        String host = URI.create(url).getHost();
        if (host == null) {
            return Decision.block("url has no host");
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (allowedHosts.contains(bare.toLowerCase(Locale.ROOT))) {
            return Decision.allow();
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException e) {
            return Decision.unresolvable("host does not resolve: " + bare);
        }
        // 任何一个解析结果是内网都拒绝：否则攻击者可以让域名同时解析出公网和内网地址碰运气
        for (InetAddress address : addresses) {
            if (isForbidden(address)) {
                return Decision.block("host resolves to a non-public address: " + address.getHostAddress());
            }
        }
        return Decision.allow();
    }

    /**
     * 严格匹配“前缀 + ID”，不允许多余路径、查询串或编码字符：
     * {@code /sink/../actuator}、{@code /sink/%2e%2e/x} 之类都不算，仍走正常的 IP 检查（并被拒绝）。
     */
    private boolean isSinkUrl(String url) {
        if (sinkPrefix == null || !url.startsWith(sinkPrefix)) {
            return false;
        }
        return SINK_ID.matcher(url.substring(sinkPrefix.length())).matches();
    }

    static boolean isForbidden(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true; // 含 0.0.0.0、127/8、169.254/16（云元数据）、10/8、172.16/12、192.168/16、fe80::/10
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return isForbiddenV4(b);
        }
        if (a instanceof Inet6Address v6) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 唯一本地地址
            }
            if (v6.isIPv4CompatibleAddress() || isNat64(b) || is6to4(b)) {
                byte[] embedded = is6to4(b)
                        ? new byte[] {b[2], b[3], b[4], b[5]}
                        : new byte[] {b[12], b[13], b[14], b[15]};
                return isForbiddenV4(embedded);
            }
        }
        return false;
    }

    private static boolean isForbiddenV4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0                                     // 0.0.0.0/8
                || first == 127                                // 环回（嵌入形式时 isLoopback 不生效）
                || first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 169 && second == 254)
                || (first == 100 && second >= 64 && second <= 127) // 100.64/10 运营商级 NAT
                || (first == 192 && second == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24 IETF 协议分配
                || (first == 198 && (second == 18 || second == 19))   // 198.18/15 基准测试
                || first >= 224;                               // 组播与保留（含 255.255.255.255）
    }

    private static boolean isNat64(byte[] b) {
        // 64:ff9b::/96
        return b[0] == 0 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0 && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0;
    }

    private static boolean is6to4(byte[] b) {
        return b[0] == 0x20 && b[1] == 0x02; // 2002::/16
    }
}
