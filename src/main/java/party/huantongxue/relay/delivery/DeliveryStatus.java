package party.huantongxue.relay.delivery;

/**
 * PENDING → IN_FLIGHT → SUCCEEDED；失败退回 PENDING（退避后重试）；超过次数 → DEAD；人工重放 DEAD → PENDING。
 */
public enum DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    SUCCEEDED,
    DEAD
}
