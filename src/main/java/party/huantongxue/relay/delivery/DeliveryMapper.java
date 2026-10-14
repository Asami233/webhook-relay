package party.huantongxue.relay.delivery;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeliveryMapper {

    /** 扇出：为一个事件的每个订阅端点各插一行 PENDING，立即可被抢。 */
    int insertPending(@Param("tenantId") long tenantId, @Param("eventId") long eventId,
                      @Param("endpointIds") List<Long> endpointIds);

    int countByEvent(long eventId);

    List<Delivery> findByEvent(@Param("tenantId") long tenantId, @Param("eventId") long eventId);

    // ---- 投递引擎 ----

    /** 必须在事务内调用：锁住到期的 PENDING 行，跳过其他实例已锁的行。 */
    List<Long> lockClaimable(int limit);

    int markInFlight(@Param("ids") List<Long> ids, @Param("leaseMs") long leaseMs);

    List<ClaimedDelivery> findClaimed(@Param("ids") List<Long> ids);

    void insertAttempt(DeliveryAttempt attempt);

    // 以下回写都以 status='IN_FLIGHT' AND attempts=#{attempts} 为条件（fencing）：
    // 租约过期后被别的实例重新抢走的任务，旧实例的迟到结果更新 0 行，不会覆盖新结果。

    int markSucceeded(@Param("id") long id, @Param("attempts") int attempts, @Param("statusCode") Integer statusCode);

    int markRetry(@Param("id") long id, @Param("attempts") int attempts, @Param("delayMs") long delayMs,
                  @Param("statusCode") Integer statusCode, @Param("error") String error);

    int markDead(@Param("id") long id, @Param("attempts") int attempts, @Param("statusCode") Integer statusCode,
                 @Param("error") String error);

    /** 没真正发出去（如等单端点许可超时）：退回 PENDING 并撤销这次计数。 */
    int releaseUnattempted(@Param("id") long id, @Param("attempts") int attempts, @Param("delayMs") long delayMs);

    /** 把租约过期的 IN_FLIGHT 退回 PENDING（实例崩溃、停机超时）。 */
    int sweepExpiredLeases(int limit);
}
