package party.huantongxue.relay.common;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** 429，并通过 Retry-After 告诉调用方多久后再试。 */
public class RateLimitedException extends ApiException {

    public RateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "ingest rate limit exceeded", retryAfter);
    }
}
