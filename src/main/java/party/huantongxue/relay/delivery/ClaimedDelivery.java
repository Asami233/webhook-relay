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
    /** 本轮已用次数 = attempts - attemptsBase（人工重放会把基数推到当时的 attempts）。 */
    private int attemptsBase;
    private String eventType;
    private String payload;
    private Instant eventCreatedAt;
    private String url;
    private byte[] secretCiphertext;
    private boolean endpointEnabled;
    private boolean endpointDeleted;
    private int maxAttempts;
    private int deliverQps;

    /** 本轮（自创建或最近一次重放以来）已用的尝试次数，含当前这次。 */
    public int attemptsThisRound() {
        return attempts - attemptsBase;
    }
}
