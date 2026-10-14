package party.huantongxue.relay.delivery;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 抢到的一条任务及投递所需的全部数据（事务提交后一次 JOIN 读出）。 */
@Getter
@Setter
public class ClaimedDelivery {
    private long id;
    private long tenantId;
    private long eventId;
    private long endpointId;
    /** 抢任务时已 +1：既是本次尝试序号，也是回写结果时的 fencing token。 */
    private int attempts;
    private String eventType;
    private String payload;
    private Instant eventCreatedAt;
    private String url;
    private byte[] secretCiphertext;
    private boolean endpointEnabled;
    private boolean endpointDeleted;
    private int maxAttempts;
    private int deliverQps;
}
