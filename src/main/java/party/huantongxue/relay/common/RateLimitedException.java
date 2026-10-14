package party.huantongxue.relay.common;

import java.time.Duration;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 429，并通过 Retry-After 告诉调用方多久后再试。 */
@Getter
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "ingest rate limit exceeded");
        this.retryAfter = retryAfter;
    }

    /** Retry-After 只能是整数秒，向上取整，至少 1 秒。 */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
