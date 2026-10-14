package party.huantongxue.relay.tenant;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Tenant {
    private Long id;
    private String name;
    private String apiKeyHash;
    private String apiKeyPrefix;
    private int ingestQps;
    private Instant createdAt;
}
