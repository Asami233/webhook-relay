package party.huantongxue.relay.event;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Event {
    private Long id;
    private long tenantId;
    private String idempotencyKey;
    private String requestHash;
    private String type;
    /** JSON 文本，原样投递给接收方。 */
    private String payload;
    private Instant createdAt;
}
