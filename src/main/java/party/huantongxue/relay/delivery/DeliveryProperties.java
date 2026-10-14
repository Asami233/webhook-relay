package party.huantongxue.relay.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 投递引擎参数。租约必须覆盖“等单端点并发许可 + 一次请求超时”，否则请求还在途时租约就过期，
 * 清扫任务会把它退回 PENDING，造成重复投递（至少一次语义允许，但应尽量避免）。
 *
 * @param enabled                false 时本实例只接收事件、不投递（测试与纯接入节点使用）
 * @param pollInterval           无任务时的轮询间隔
 * @param batchSize              每次最多抢多少条
 * @param maxConcurrency         本实例同时在途的投递上限
 * @param perEndpointConcurrency 单个端点同时在途的上限，防止一个慢端点占满全部并发
 * @param connectTimeout         建连超时
 * @param requestTimeout         整个请求（含读响应头）的超时
 * @param lease                  租约时长
 * @param backoffBase            第 1 次失败后的基础退避
 * @param backoffMax             退避上限
 * @param sweepInterval          清扫过期租约的间隔
 * @param shutdownTimeout        优雅停机时等待在途投递的上限
 * @param breakerThreshold       连续多少次可重试失败后熔断该端点
 * @param breakerCooldown        熔断打开的时长
 */
@ConfigurationProperties("relay.delivery")
public record DeliveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("200") int maxConcurrency,
        @DefaultValue("10") int perEndpointConcurrency,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("5s") Duration backoffBase,
        @DefaultValue("1h") Duration backoffMax,
        @DefaultValue("10s") Duration sweepInterval,
        @DefaultValue("20s") Duration shutdownTimeout,
        @DefaultValue("10") int breakerThreshold,
        @DefaultValue("60s") Duration breakerCooldown) {

    public DeliveryProperties {
        if (batchSize < 1 || maxConcurrency < 1 || perEndpointConcurrency < 1 || breakerThreshold < 1) {
            throw new IllegalArgumentException("relay.delivery batch/concurrency settings must be >= 1");
        }
        if (lease.compareTo(requestTimeout.plus(connectTimeout).plusSeconds(5)) < 0) {
            throw new IllegalArgumentException("relay.delivery.lease must exceed connect + request timeout by at least 5s");
        }
    }

    /** 等单端点许可的最长时间：剩余租约扣掉一次请求的最坏耗时和 5 秒余量。 */
    Duration endpointPermitWait() {
        return lease.minus(requestTimeout).minus(connectTimeout).minusSeconds(5);
    }
}
