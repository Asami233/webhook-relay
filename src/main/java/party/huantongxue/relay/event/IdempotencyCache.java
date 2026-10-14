package party.huantongxue.relay.event;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.security.Tokens;

/**
 * 幂等快速路径：客户端超时重试时，重复请求在 Redis 命中就直接返回首次结果，不再碰数据库。
 *
 * <p>它只是缓存，<b>正确性仍由 MySQL 唯一索引保证</b>：只在事务提交之后写入（不会缓存回滚掉的结果）；
 * 未命中、过期或 Redis 故障都会回落到数据库路径，结果一致，只是慢一点。
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public IdempotencyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Entry(long eventId, int deliveries, String requestHash) {
    }

    public Optional<Entry> get(long tenantId, String idempotencyKey) {
        try {
            String value = redis.opsForValue().get(key(tenantId, idempotencyKey));
            return Optional.ofNullable(value).flatMap(IdempotencyCache::parse);
        } catch (DataAccessException e) {
            log.warn("idempotency cache unavailable, falling back to database: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long tenantId, String idempotencyKey, Entry entry) {
        try {
            redis.opsForValue().set(key(tenantId, idempotencyKey),
                    entry.eventId() + "|" + entry.deliveries() + "|" + entry.requestHash(), TTL);
        } catch (DataAccessException e) {
            log.warn("idempotency cache write failed: {}", e.getMessage());
        }
    }

    /** 幂等键最长 255 字符，取哈希让 Redis 键长度固定。 */
    static String key(long tenantId, String idempotencyKey) {
        return "relay:idem:" + tenantId + ":" + Tokens.sha256Hex(idempotencyKey);
    }

    private static Optional<Entry> parse(String value) {
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), parts[2]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
