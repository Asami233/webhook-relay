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

    /**
     * @param delay 放行时：调用方需要先等待的时间（预约，通常为 0）；拒绝时：建议的重试间隔
     */
    public record Result(boolean allowed, Duration delay) {
    }

    /**
     * 取一个令牌，不足即拒绝（接收侧限流用：超限立即 429）。
     *
     * @param key           桶的 Redis 键
     * @param ratePerSecond 每秒补充速率
     * @param burst         桶容量
     */
    public Result tryAcquire(String key, double ratePerSecond, int burst) {
        return reserve(key, ratePerSecond, burst, Duration.ZERO);
    }

    /**
     * 取一个令牌；不足但等待不超过 maxWait 时预约成功，返回需要先等多久（投递侧限流用）。
     */
    public Result reserve(String key, double ratePerSecond, int burst, Duration maxWait) {
        try {
            List<?> reply = redis.execute(SCRIPT, List.of(key), Double.toString(ratePerSecond),
                    Integer.toString(burst), "1", Long.toString(Math.max(0, maxWait.toMillis())));
            boolean allowed = ((Number) reply.get(0)).longValue() == 1;
            long waitMs = ((Number) reply.get(1)).longValue();
            return new Result(allowed, allowed ? Duration.ofMillis(waitMs) : Duration.ofMillis(Math.max(1, waitMs)));
        } catch (DataAccessException e) {
            log.warn("rate limiter unavailable, allowing request: {}", e.getMessage());
            return new Result(true, Duration.ZERO);
        }
    }
}
