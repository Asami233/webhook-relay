package party.huantongxue.relay.delivery;

/** 事务已提交、有新的待投递任务：本实例的投递循环可以立即去抢，不必等轮询。 */
public record DeliveriesCreated(long eventId, int deliveries) {
}
