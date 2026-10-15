package party.huantongxue.relay.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.delivery.BacklogStats;
import party.huantongxue.relay.delivery.DeliveryMapper;
import party.huantongxue.relay.delivery.DeliveryStatus;
import party.huantongxue.relay.delivery.StatusCount;

/**
 * 定时从数据库刷新积压类指标，Prometheus 抓取时只读内存值：抓取频率、实例数都不会放大数据库查询。
 * 这些是全局值，每个实例上报的一样，看板里用 max() 聚合即可。
 */
@Component
public class BacklogMetricsRefresher {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetricsRefresher.class);
    private static final DeliveryStatus[] OPEN = {DeliveryStatus.PENDING, DeliveryStatus.IN_FLIGHT, DeliveryStatus.DEAD};

    private final DeliveryMapper deliveries;
    private final RelayMetrics metrics;

    public BacklogMetricsRefresher(DeliveryMapper deliveries, RelayMetrics metrics) {
        this.deliveries = deliveries;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${relay.metrics.refresh-interval:5s}", initialDelayString = "${relay.metrics.refresh-interval:5s}")
    public void refresh() {
        try {
            BacklogStats backlog = deliveries.backlogStats();
            metrics.backlog(backlog.getDue(), backlog.getOldestDueSeconds());
            // GROUP BY 不返回 0 行的状态，先全部置 0 再填，避免旧值残留
            for (DeliveryStatus s : OPEN) {
                metrics.statusCount(s, 0);
            }
            for (StatusCount c : deliveries.countOpenByStatus()) {
                metrics.statusCount(c.getStatus(), c.getN());
            }
        } catch (RuntimeException e) {
            log.warn("backlog metrics refresh failed: {}", e.getMessage());
        }
    }
}
