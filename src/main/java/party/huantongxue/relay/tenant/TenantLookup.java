package party.huantongxue.relay.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 按 API Key 哈希查租户，带本地缓存。
 *
 * <p>D5 压测发现：每个接收请求要借两次数据库连接（鉴权查租户 + 写事务），连接池 10 个时吞吐卡在约 400 次/秒。
 * 租户几乎不变，缓存后每个请求只借一次连接。
 *
 * <ul>
 *   <li>只缓存命中：随机伪造的 Key 不会进缓存，无法用来撑爆内存；它们照常查库（D3 的接收限流在鉴权之后，
 *       大量无效 Key 属于另一类防护：网关/WAF 层的 IP 限流）。</li>
 *   <li>轮换 Key 时主动失效本实例的旧条目；其他实例最多在 {@link #TTL} 内仍接受旧 Key——这是可接受的取舍，
 *       若要求立即失效，可改为 Redis 广播失效或把 TTL 调短。</li>
 * </ul>
 */
@Component
public class TenantLookup {

    static final Duration TTL = Duration.ofSeconds(30);

    private final TenantMapper tenants;
    private final Cache<String, Tenant> byKeyHash = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(10_000)
            .build();

    public TenantLookup(TenantMapper tenants) {
        this.tenants = tenants;
    }

    /** @return 租户；Key 不存在时为 null */
    public Tenant findByApiKeyHash(String apiKeyHash) {
        Tenant cached = byKeyHash.getIfPresent(apiKeyHash);
        if (cached != null) {
            return cached;
        }
        Tenant tenant = tenants.findByApiKeyHash(apiKeyHash);
        if (tenant != null) {
            byKeyHash.put(apiKeyHash, tenant);
        }
        return tenant;
    }

    public void evict(String apiKeyHash) {
        byKeyHash.invalidate(apiKeyHash);
    }
}
