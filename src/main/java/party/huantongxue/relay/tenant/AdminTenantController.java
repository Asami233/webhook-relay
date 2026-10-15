package party.huantongxue.relay.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.security.Tokens;

/** 运维接口：租户由管理员开通，API Key 明文只在创建/轮换的响应里出现一次。 */
@RestController
@RequestMapping("/api/admin/tenants")
public class AdminTenantController {

    private final TenantMapper tenants;
    private final TenantLookup lookup;

    public AdminTenantController(TenantMapper tenants, TenantLookup lookup) {
        this.tenants = tenants;
        this.lookup = lookup;
    }

    public record CreateTenant(@NotBlank @Size(max = 100) String name,
                               @Min(1) @Max(10_000) Integer ingestQps) {
    }

    public record TenantView(long id, String name, String apiKeyPrefix, int ingestQps, Instant createdAt) {
        static TenantView of(Tenant t) {
            return new TenantView(t.getId(), t.getName(), t.getApiKeyPrefix(), t.getIngestQps(), t.getCreatedAt());
        }
    }

    public record TenantWithKey(TenantView tenant, String apiKey) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantWithKey create(@Valid @RequestBody CreateTenant request) {
        String apiKey = Tokens.newApiKey();
        Tenant tenant = new Tenant();
        tenant.setName(request.name().trim());
        tenant.setApiKeyHash(Tokens.sha256Hex(apiKey));
        tenant.setApiKeyPrefix(Tokens.displayPrefix(apiKey));
        tenant.setIngestQps(request.ingestQps() == null ? 50 : request.ingestQps());
        tenants.insert(tenant);
        return new TenantWithKey(TenantView.of(tenants.findById(tenant.getId())), apiKey);
    }

    @GetMapping
    public List<TenantView> list() {
        return tenants.findAll().stream().map(TenantView::of).toList();
    }

    @PostMapping("/{id}/rotate-key")
    public TenantWithKey rotateKey(@PathVariable long id) {
        Tenant current = tenants.findById(id);
        if (current == null) {
            throw ApiException.notFound("tenant");
        }
        String apiKey = Tokens.newApiKey();
        if (tenants.updateApiKey(id, Tokens.sha256Hex(apiKey), Tokens.displayPrefix(apiKey)) == 0) {
            throw ApiException.notFound("tenant");
        }
        // 本实例立即失效旧 Key；其他实例最多 TenantLookup.TTL 后失效
        lookup.evict(current.getApiKeyHash());
        return new TenantWithKey(TenantView.of(tenants.findById(id)), apiKey);
    }
}
