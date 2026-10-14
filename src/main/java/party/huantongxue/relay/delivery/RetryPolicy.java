package party.huantongxue.relay.delivery;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** 结果分类与退避计算，纯函数，便于单测。 */
public final class RetryPolicy {

    public enum Outcome {
        SUCCESS,
        /** 网络错误、超时、408、429、5xx：对方可能稍后恢复。 */
        RETRY,
        /** 其他 3xx/4xx：重试也不会变，直接死信，等人工处理后重放。 */
        PERMANENT
    }

    private RetryPolicy() {
    }

    public static Outcome classify(Integer statusCode) {
        if (statusCode == null) {
            return Outcome.RETRY;
        }
        if (statusCode >= 200 && statusCode < 300) {
            return Outcome.SUCCESS;
        }
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return Outcome.RETRY;
        }
        return Outcome.PERMANENT;
    }

    /**
     * 指数退避 + 抖动（equal jitter）：上限内取 {@code base * 2^(attempt-1)}，实际等待在其 [1/2, 1] 区间随机。
     * 抖动让同一时刻失败的大量任务错开重试，避免对方恢复瞬间被“惊群”再次打垮。
     * 对方给出 Retry-After 时取两者较大值，但仍不超过上限。
     */
    public static Duration backoff(int attempt, Duration base, Duration max, Duration retryAfter, RandomGenerator random) {
        long baseMs = Math.max(1, base.toMillis());
        long maxMs = Math.max(baseMs, max.toMillis());
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long expMs = Math.min(maxMs, baseMs << shift);
        long half = expMs / 2;
        long delay = half + random.nextLong(expMs - half + 1);
        if (retryAfter != null) {
            delay = Math.max(delay, Math.min(retryAfter.toMillis(), maxMs));
        }
        return Duration.ofMillis(delay);
    }

    /** 只支持秒数形式的 Retry-After；HTTP 日期形式少见，忽略即可。 */
    public static Duration parseRetryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
