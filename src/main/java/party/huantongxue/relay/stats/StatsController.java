package party.huantongxue.relay.stats;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.delivery.DeliveryStatus;
import party.huantongxue.relay.delivery.StatusCount;
import party.huantongxue.relay.security.TenantPrincipal;

/** 控制台概览：本租户的投递状态分布与最近一小时每分钟的尝试成败。 */
@RestController
public class StatsController {

    static final int WINDOW_MINUTES = 60;

    private final StatsMapper stats;

    public StatsController(StatsMapper stats) {
        this.stats = stats;
    }

    public record Point(Instant minute, long ok, long failed) {
    }

    public record Overview(long events, Map<DeliveryStatus, Long> deliveries, List<Point> attemptsPerMinute) {
    }

    @GetMapping("/api/v1/stats")
    public Overview overview(TenantPrincipal tenant) {
        Map<DeliveryStatus, Long> byStatus = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus s : DeliveryStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (StatusCount c : stats.countByStatus(tenant.id())) {
            byStatus.put(c.getStatus(), c.getN());
        }
        return new Overview(stats.countEvents(tenant.id()), byStatus,
                fillMinutes(stats.attemptsPerMinute(tenant.id(), WINDOW_MINUTES), Instant.now()));
    }

    /** 补齐没有尝试的分钟，前端直接按 60 个点画图。 */
    static List<Point> fillMinutes(List<MinuteCount> rows, Instant now) {
        Map<Instant, MinuteCount> byMinute = new HashMap<>();
        for (MinuteCount r : rows) {
            byMinute.put(r.getMinute().truncatedTo(ChronoUnit.MINUTES), r);
        }
        Instant end = now.truncatedTo(ChronoUnit.MINUTES);
        List<Point> points = new ArrayList<>(WINDOW_MINUTES);
        for (int i = WINDOW_MINUTES - 1; i >= 0; i--) {
            Instant m = end.minus(i, ChronoUnit.MINUTES);
            MinuteCount r = byMinute.get(m);
            points.add(new Point(m, r == null ? 0 : r.getOk(), r == null ? 0 : r.getFailed()));
        }
        return points;
    }
}
