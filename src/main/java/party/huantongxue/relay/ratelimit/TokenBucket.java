package party.huantongxue.relay.ratelimit;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis + Lua 令牌桶。选令牌桶而不是固定窗口：允许短时突发（桶容量），长期速率仍被限制，
 * 且没有固定窗口在窗口边界处“两倍流量”的问题。Lua 脚本保证“读-算-写”原子，多实例共享同一个桶。
 *
 * <p>Redis 不可用时放行（fail-open）：限流是保护措施，不应因为它的依赖故障把正常请求全部拒掉。
 */
@Component
public class TokenBucket {

    private static final Logger log = LoggerFactory.getLogger(TokenBucket.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public TokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @param retryAfter 被拒绝时建议的等待时间；放行时为 {@link Duration#ZERO} */
    public record Result(boolean allowed, Duration retryAfter) {
    }

    /**
     * 取一个令牌。
     *
     * @param key      桶的 Redis 键
     * @param ratePerSecond 每秒补充速率
     * @param burst    桶容量
     */
    public Result tryAcquire(String key, double ratePerSecond, int burst) {
        try {
            List<?> reply = redis.execute(SCRIPT, List.of(key),
                    Double.toString(ratePerSecond), Integer.toString(burst), "1");
            boolean allowed = ((Number) reply.get(0)).longValue() == 1;
            long waitMs = ((Number) reply.get(1)).longValue();
            return new Result(allowed, allowed ? Duration.ZERO : Duration.ofMillis(Math.max(1, waitMs)));
        } catch (DataAccessException e) {
            log.warn("rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Result(true, Duration.ZERO);
        }
    }
}
