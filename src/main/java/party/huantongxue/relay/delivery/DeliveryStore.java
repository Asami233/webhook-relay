package party.huantongxue.relay.delivery;

import java.time.Duration;
import java.util.List;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 投递引擎的数据库边界：每个方法是一个短事务，网络请求永远在事务之外。 */
@Component
public class DeliveryStore {

    private final DeliveryMapper mapper;
    private final TransactionTemplate tx;

    public DeliveryStore(DeliveryMapper mapper, TransactionTemplate tx) {
        this.mapper = mapper;
        this.tx = tx;
    }

    /**
     * 抢任务：同一事务里 {@code SELECT … FOR UPDATE SKIP LOCKED} 并改为 IN_FLIGHT、设租约，然后提交释放行锁。
     * 提交之后才读取投递所需数据并发 HTTP —— 若把 HTTP 放进事务，慢端点会让行锁和连接一直被占着。
     */
    public List<ClaimedDelivery> claim(int limit, Duration lease) {
        List<Long> ids = tx.execute(status -> {
            List<Long> locked = mapper.lockClaimable(limit);
            if (!locked.isEmpty()) {
                mapper.markInFlight(locked, lease.toMillis());
            }
            return locked;
        });
        return ids == null || ids.isEmpty() ? List.of() : mapper.findClaimed(ids);
    }

    public boolean succeeded(ClaimedDelivery d, DeliveryAttempt attempt) {
        return recordAndUpdate(attempt, () -> mapper.markSucceeded(d.getId(), d.getAttempts(), attempt.getStatusCode()));
    }

    public boolean retry(ClaimedDelivery d, DeliveryAttempt attempt, Duration delay) {
        return recordAndUpdate(attempt, () -> mapper.markRetry(d.getId(), d.getAttempts(), delay.toMillis(),
                attempt.getStatusCode(), attempt.getError()));
    }

    /** attempt 为 null 表示没有发请求（端点已停用等），只改状态。 */
    public boolean dead(ClaimedDelivery d, DeliveryAttempt attempt, String reason) {
        Integer code = attempt == null ? null : attempt.getStatusCode();
        return recordAndUpdate(attempt, () -> mapper.markDead(d.getId(), d.getAttempts(), code, truncate(reason, 512)));
    }

    public boolean releaseUnattempted(ClaimedDelivery d, Duration delay) {
        return mapper.releaseUnattempted(d.getId(), d.getAttempts(), delay.toMillis()) == 1;
    }

    public int sweepExpiredLeases(int limit) {
        return mapper.sweepExpiredLeases(limit);
    }

    /**
     * 尝试记录与状态更新同一事务。返回 false 表示租约已丢（被清扫后别的实例重新抢走）：
     * 尝试记录照样保留——请求确实发出去了，审计上应该看得到。
     */
    private boolean recordAndUpdate(DeliveryAttempt attempt, IntSupplier update) {
        Boolean updated = tx.execute(status -> {
            if (attempt != null) {
                mapper.insertAttempt(attempt);
            }
            return update.getAsInt() == 1;
        });
        return Boolean.TRUE.equals(updated);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
