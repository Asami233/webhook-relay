package party.huantongxue.relay.delivery;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Delivery {
    private Long id;
    private long tenantId;
    private long eventId;
    private long endpointId;
    private DeliveryStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private Integer lastStatusCode;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
}
