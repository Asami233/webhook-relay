package party.huantongxue.relay.delivery;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.ratelimit.TokenBucket;

/**
 * 投递侧对单个端点的保护，状态放在 Redis，多实例共享：
 *
 * <ul>
 *   <li><b>限流</b>：按端点 {@code deliver_qps} 的令牌桶，保护接收方不被积压的重试一下子冲垮。</li>
 *   <li><b>熔断</b>：连续可重试失败达到阈值后“打开”一段冷却期，期间不再向它发请求，
 *       避免持续超时的端点占满并发、拖慢其他端点。冷却结束后放行；若下一次仍失败立刻再次打开
 *       （打开时把计数置为阈值 - 1，近似半开状态的“一次试探”）。</li>
 * </ul>
 *
 * <p>被限流或熔断的任务不算一次尝试，退回 PENDING 稍后再抢。Redis 故障时全部放行（fail-open）。
 */
@Component
public class EndpointGuard {

    private static final Logger log = LoggerFactory.getLogger(EndpointGuard.class);
    private static final Duration FAILURE_COUNTER_TTL = Duration.ofHours(1);

    private final TokenBucket bucket;
    private final StringRedisTemplate redis;

    public EndpointGuard(TokenBucket bucket, StringRedisTemplate redis) {
        this.bucket = bucket;
        this.redis = redis;
    }

    /**
     * 投递侧限流，带预约：等待不超过 maxWait 时直接占下令牌，调用方睡 {@code delay} 后发送。
     *
     * @return allowed=true 时 delay 为先要等待的时间；allowed=false 时 delay 为建议多久后再抢
     */
    public TokenBucket.Result reserveDelivery(long endpointId, int deliverQps, Duration maxWait) {
        return bucket.reserve("relay:rl:deliver:" + endpointId, deliverQps, deliverQps, maxWait);
    }

    /** @return 熔断剩余时间；为 null 表示熔断关闭 */
    public Duration openFor(long endpointId) {
        try {
            Long ttl = redis.getExpire(openKey(endpointId), TimeUnit.MILLISECONDS);
            return ttl != null && ttl > 0 ? Duration.ofMillis(ttl) : null;
        } catch (DataAccessException e) {
            log.warn("circuit breaker state unavailable, treating as closed: {}", e.getMessage());
            return null;
        }
    }

    public void recordSuccess(long endpointId) {
        try {
            redis.delete(failuresKey(endpointId));
        } catch (DataAccessException e) {
            log.warn("circuit breaker update failed: {}", e.getMessage());
        }
    }

    /** @return true 表示这次失败让熔断打开 */
    public boolean recordFailure(long endpointId, int threshold, Duration cooldown) {
        try {
            Long failures = redis.opsForValue().increment(failuresKey(endpointId));
            redis.expire(failuresKey(endpointId), FAILURE_COUNTER_TTL);
            if (failures != null && failures >= threshold) {
                redis.opsForValue().set(openKey(endpointId), "1", cooldown);
                redis.opsForValue().set(failuresKey(endpointId), Integer.toString(threshold - 1), FAILURE_COUNTER_TTL);
                log.warn("circuit opened for endpoint {} for {} after {} consecutive failures",
                        endpointId, cooldown, failures);
                return true;
            }
        } catch (DataAccessException e) {
            log.warn("circuit breaker update failed: {}", e.getMessage());
        }
        return false;
    }

    static String openKey(long endpointId) {
        return "relay:cb:" + endpointId + ":open";
    }

    static String failuresKey(long endpointId) {
        return "relay:cb:" + endpointId + ":failures";
    }
}
