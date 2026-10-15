package party.huantongxue.relay.delivery;

import lombok.Getter;
import lombok.Setter;

/** 已到期但还没被抢走的任务数，以及其中最老的一条等了多久（消费跟不上时会持续上涨）。 */
@Getter
@Setter
public class BacklogStats {
    private long due;
    private double oldestDueSeconds;
}
