package party.huantongxue.relay.endpoint;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Endpoint {
    private Long id;
    private long tenantId;
    private String url;
    private String description;
    private byte[] secretCiphertext;
    /** 逗号分隔的订阅模式，见 {@link EventTypes}。 */
    private String eventTypes;
    private boolean enabled;
    private int maxAttempts;
    private int deliverQps;
    private Instant createdAt;
    private Instant updatedAt;
}
