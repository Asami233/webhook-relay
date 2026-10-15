package party.huantongxue.relay.delivery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.endpoint.EndpointService;
import party.huantongxue.relay.security.TenantPrincipal;

/** 投递查询与死信人工重放（控制台使用）。 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class DeliveryController {

    private final DeliveryMapper deliveries;
    private final EndpointService endpoints;

    public DeliveryController(DeliveryMapper deliveries, EndpointService endpoints) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
    }

    public record DeliverySummary(long id, long eventId, long endpointId, DeliveryStatus status, int attempts,
                                  int replayCount, Instant nextAttemptAt, Integer lastStatusCode, String lastError,
                                  Instant createdAt, Instant updatedAt) {
        static DeliverySummary of(Delivery d) {
            return new DeliverySummary(d.getId(), d.getEventId(), d.getEndpointId(), d.getStatus(), d.getAttempts(),
                    d.getReplayCount(), d.getNextAttemptAt(), d.getLastStatusCode(), d.getLastError(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record AttemptView(int attemptNo, Instant startedAt, int durationMs, Integer statusCode, String error,
                              String responseSnippet) {
        static AttemptView of(DeliveryAttempt a) {
            return new AttemptView(a.getAttemptNo(), a.getStartedAt(), a.getDurationMs(), a.getStatusCode(),
                    a.getError(), a.getResponseSnippet());
        }
    }

    public record DeliveryDetail(DeliverySummary delivery, List<AttemptView> attempts) {
    }

    public record Replayed(int replayed) {
    }

    @GetMapping("/deliveries")
    public List<DeliverySummary> list(TenantPrincipal tenant,
                                      @RequestParam(required = false) DeliveryStatus status,
                                      @RequestParam(required = false) Long before,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return deliveries.findPage(tenant.id(), status, before, limit).stream().map(DeliverySummary::of).toList();
    }

    @GetMapping("/deliveries/{id}")
    public DeliveryDetail get(TenantPrincipal tenant, @PathVariable long id) {
        Delivery d = require(tenant, id);
        return new DeliveryDetail(DeliverySummary.of(d),
                deliveries.findAttempts(id).stream().map(AttemptView::of).toList());
    }

    /** DEAD → PENDING，本轮重新获得端点 max_attempts 次机会；尝试历史保留。 */
    @PostMapping("/deliveries/{id}/replay")
    public DeliverySummary replay(TenantPrincipal tenant, @PathVariable long id) {
        if (deliveries.replay(tenant.id(), id) == 0) {
            Delivery d = require(tenant, id);
            if (d.getStatus() != DeliveryStatus.DEAD) {
                throw new ApiException(HttpStatus.CONFLICT, "not_dead", "only DEAD deliveries can be replayed");
            }
            throw new ApiException(HttpStatus.CONFLICT, "endpoint_disabled",
                    "endpoint is disabled or deleted; enable it before replaying");
        }
        return DeliverySummary.of(require(tenant, id));
    }

    /** 接收方修好之后，一次性重放该端点的全部死信。 */
    @PostMapping("/endpoints/{id}/replay-dead")
    public Replayed replayDeadForEndpoint(TenantPrincipal tenant, @PathVariable long id) {
        if (!endpoints.get(tenant.id(), id).isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "endpoint_disabled", "enable the endpoint before replaying");
        }
        return new Replayed(deliveries.replayDeadForEndpoint(tenant.id(), id));
    }

    private Delivery require(TenantPrincipal tenant, long id) {
        Delivery d = deliveries.findById(tenant.id(), id);
        if (d == null) {
            throw ApiException.notFound("delivery");
        }
        return d;
    }
}
