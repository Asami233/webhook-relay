package party.huantongxue.relay.event;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.common.RateLimitedException;
import party.huantongxue.relay.delivery.Delivery;
import party.huantongxue.relay.delivery.DeliveryMapper;
import party.huantongxue.relay.delivery.DeliveryStatus;
import party.huantongxue.relay.metrics.RelayMetrics;
import party.huantongxue.relay.ratelimit.TokenBucket;
import party.huantongxue.relay.security.TenantPrincipal;
import tools.jackson.databind.JsonNode;

@Validated
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final EventService service;
    private final EventMapper events;
    private final DeliveryMapper deliveries;
    private final TokenBucket rateLimiter;
    private final RelayMetrics metrics;
    private final IngestAdmission admission;

    public EventController(EventService service, EventMapper events, DeliveryMapper deliveries,
                           TokenBucket rateLimiter, RelayMetrics metrics, IngestAdmission admission) {
        this.service = service;
        this.events = events;
        this.deliveries = deliveries;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.admission = admission;
    }

    public record IngestRequest(@NotBlank String type, @NotNull JsonNode payload) {
    }

    public record Accepted(long eventId, int deliveries) {
    }

    public record EventSummary(long id, String type, String idempotencyKey, Instant createdAt) {
        static EventSummary of(Event e) {
            return new EventSummary(e.getId(), e.getType(), e.getIdempotencyKey(), e.getCreatedAt());
        }
    }

    public record DeliveryView(long id, long endpointId, DeliveryStatus status, int attempts, Instant nextAttemptAt,
                               Integer lastStatusCode, String lastError, Instant updatedAt) {
        static DeliveryView of(Delivery d) {
            return new DeliveryView(d.getId(), d.getEndpointId(), d.getStatus(), d.getAttempts(), d.getNextAttemptAt(),
                    d.getLastStatusCode(), d.getLastError(), d.getUpdatedAt());
        }
    }

    public record EventDetail(long id, String type, String idempotencyKey, @JsonRawValue String payload,
                              Instant createdAt, List<DeliveryView> deliveries) {
    }

    /**
     * 202：已持久化、尚未投递。重复的 Idempotency-Key 返回首次结果并带 {@value #REPLAYED_HEADER}: true。
     * 先过实例并发上限（过载 503），再按租户限流（429；重放也计数，否则可借重放绕过限流），再走幂等与写库。
     */
    @PostMapping
    public ResponseEntity<Accepted> ingest(TenantPrincipal tenant,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody IngestRequest request) {
        try {
            admission.enter();
        } catch (ApiException overloaded) {
            metrics.ingest(RelayMetrics.IngestResult.OVERLOADED);
            throw overloaded;
        }
        try {
            TokenBucket.Result limit = rateLimiter.tryAcquire("relay:rl:ingest:" + tenant.id(),
                    tenant.ingestQps(), tenant.ingestQps());
            if (!limit.allowed()) {
                metrics.ingest(RelayMetrics.IngestResult.RATE_LIMITED);
                throw new RateLimitedException(limit.delay());
            }
            EventService.IngestResult result = service.ingest(tenant.id(), idempotencyKey, request.type(),
                    request.payload().toString());
            metrics.ingest(result.replayed() ? RelayMetrics.IngestResult.REPLAYED : RelayMetrics.IngestResult.ACCEPTED);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                    .body(new Accepted(result.eventId(), result.deliveries()));
        } finally {
            admission.exit();
        }
    }

    @GetMapping
    public List<EventSummary> list(TenantPrincipal tenant,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return events.findPage(tenant.id(), before, limit).stream().map(EventSummary::of).toList();
    }

    @GetMapping("/{id}")
    public EventDetail get(TenantPrincipal tenant, @PathVariable long id) {
        Event e = events.findById(tenant.id(), id);
        if (e == null) {
            throw ApiException.notFound("event");
        }
        List<DeliveryView> ds = deliveries.findByEvent(tenant.id(), id).stream().map(DeliveryView::of).toList();
        return new EventDetail(e.getId(), e.getType(), e.getIdempotencyKey(), e.getPayload(), e.getCreatedAt(), ds);
    }
}
