package party.huantongxue.relay.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import party.huantongxue.relay.delivery.RetryPolicy.Outcome;

class RetryPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(5);
    private static final Duration MAX = Duration.ofHours(1);

    @Test
    void classifiesStatusCodes() {
        assertThat(RetryPolicy.classify(200)).isEqualTo(Outcome.SUCCESS);
        assertThat(RetryPolicy.classify(204)).isEqualTo(Outcome.SUCCESS);
        assertThat(RetryPolicy.classify(null)).isEqualTo(Outcome.RETRY);
        assertThat(RetryPolicy.classify(408)).isEqualTo(Outcome.RETRY);
        assertThat(RetryPolicy.classify(429)).isEqualTo(Outcome.RETRY);
        assertThat(RetryPolicy.classify(503)).isEqualTo(Outcome.RETRY);
        assertThat(RetryPolicy.classify(301)).isEqualTo(Outcome.PERMANENT);
        assertThat(RetryPolicy.classify(400)).isEqualTo(Outcome.PERMANENT);
        assertThat(RetryPolicy.classify(404)).isEqualTo(Outcome.PERMANENT);
    }

    @Test
    void backoffGrowsExponentiallyWithinJitterBounds() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        for (int attempt = 1; attempt <= 6; attempt++) {
            long exp = BASE.toMillis() << (attempt - 1);
            for (int i = 0; i < 200; i++) {
                long ms = RetryPolicy.backoff(attempt, BASE, MAX, null, random).toMillis();
                assertThat(ms).isBetween(exp / 2, exp);
            }
        }
    }

    @Test
    void backoffIsCappedAndNeverOverflows() {
        RandomGenerator random = RandomGenerator.getDefault();
        assertThat(RetryPolicy.backoff(20, BASE, MAX, null, random)).isLessThanOrEqualTo(MAX);
        assertThat(RetryPolicy.backoff(1000, BASE, MAX, null, random)).isLessThanOrEqualTo(MAX).isPositive();
    }

    @Test
    void retryAfterExtendsDelayButNotBeyondCap() {
        RandomGenerator random = RandomGenerator.getDefault();
        assertThat(RetryPolicy.backoff(1, BASE, MAX, Duration.ofSeconds(120), random)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryPolicy.backoff(1, BASE, MAX, Duration.ofDays(1), random)).isEqualTo(MAX);
        assertThat(RetryPolicy.parseRetryAfter(" 30 ")).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(RetryPolicy.parseRetryAfter("-1")).isNull();
    }
}
