package party.huantongxue.relay.stats;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MinuteCount {
    private Instant minute;
    private long ok;
    private long failed;
}
