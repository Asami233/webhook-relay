package party.huantongxue.relay.delivery;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusCount {
    private DeliveryStatus status;
    private long n;
}
