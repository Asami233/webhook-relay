package party.huantongxue.relay.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import party.huantongxue.relay.common.ApiException;

class IngestAdmissionTest {

    @Test
    void rejectsBeyondCapacityWithRetryAfterAndRecoversOnExit() {
        IngestAdmission admission = new IngestAdmission(2);
        admission.enter();
        admission.enter();
        assertThatThrownBy(admission::enter)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("overloaded");
                    assertThat(e.retryAfterSeconds()).isEqualTo(1);
                });
        admission.exit();
        admission.enter(); // 释放一个后又能进入
    }
}
