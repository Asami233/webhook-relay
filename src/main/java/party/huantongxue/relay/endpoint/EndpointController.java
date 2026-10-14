package party.huantongxue.relay.endpoint;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.security.TenantPrincipal;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService service;

    public EndpointController(EndpointService service) {
        this.service = service;
    }

    public record CreateEndpoint(@NotBlank String url,
                                 @Size(max = 255) String description,
                                 @NotEmpty @Size(max = 50) List<String> eventTypes,
                                 @Min(1) @Max(30) Integer maxAttempts,
                                 @Min(1) @Max(1000) Integer deliverQps) {
    }

    public record UpdateEndpoint(String url,
                                 @Size(max = 255) String description,
                                 @Size(min = 1, max = 50) List<String> eventTypes,
                                 Boolean enabled,
                                 @Min(1) @Max(30) Integer maxAttempts,
                                 @Min(1) @Max(1000) Integer deliverQps) {
    }

    /** 不含 secret；secret 只在创建与轮换时返回。 */
    public record EndpointView(long id, String url, String description, List<String> eventTypes, boolean enabled,
                               int maxAttempts, int deliverQps, Instant createdAt, Instant updatedAt) {
        static EndpointView of(Endpoint e) {
            return new EndpointView(e.getId(), e.getUrl(), e.getDescription(), EventTypes.split(e.getEventTypes()),
                    e.isEnabled(), e.getMaxAttempts(), e.getDeliverQps(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record EndpointWithSecret(EndpointView endpoint, String secret) {
    }

    public record SecretView(String secret) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointWithSecret create(TenantPrincipal tenant, @Valid @RequestBody CreateEndpoint r) {
        EndpointService.Created created = service.create(tenant.id(), r.url(), r.description(), r.eventTypes(),
                r.maxAttempts(), r.deliverQps());
        return new EndpointWithSecret(EndpointView.of(created.endpoint()), created.secret());
    }

    @GetMapping
    public List<EndpointView> list(TenantPrincipal tenant) {
        return service.list(tenant.id()).stream().map(EndpointView::of).toList();
    }

    @GetMapping("/{id}")
    public EndpointView get(TenantPrincipal tenant, @PathVariable long id) {
        return EndpointView.of(service.get(tenant.id(), id));
    }

    @PatchMapping("/{id}")
    public EndpointView update(TenantPrincipal tenant, @PathVariable long id, @Valid @RequestBody UpdateEndpoint r) {
        return EndpointView.of(service.update(tenant.id(), id, r.url(), r.description(), r.eventTypes(),
                r.enabled(), r.maxAttempts(), r.deliverQps()));
    }

    @PostMapping("/{id}/rotate-secret")
    public SecretView rotateSecret(TenantPrincipal tenant, @PathVariable long id) {
        return new SecretView(service.rotateSecret(tenant.id(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(TenantPrincipal tenant, @PathVariable long id) {
        service.delete(tenant.id(), id);
    }
}
