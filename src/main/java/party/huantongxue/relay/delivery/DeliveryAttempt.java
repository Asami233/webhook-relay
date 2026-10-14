package party.huantongxue.relay.delivery;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeliveryAttempt {
    private Long id;
    private long deliveryId;
    private int attemptNo;
    private Instant startedAt;
    private int durationMs;
    private Integer statusCode;
    private String error;
    private String responseSnippet;
}
