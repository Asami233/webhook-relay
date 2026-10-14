package party.huantongxue.relay.endpoint;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.security.SecretCipher;
import party.huantongxue.relay.security.Tokens;
import party.huantongxue.relay.ssrf.SsrfGuard;

@Service
public class EndpointService {

    static final int DEFAULT_MAX_ATTEMPTS = 8;
    static final int DEFAULT_DELIVER_QPS = 20;

    private final EndpointMapper endpoints;
    private final SecretCipher cipher;
    private final SsrfGuard ssrf;

    public EndpointService(EndpointMapper endpoints, SecretCipher cipher, SsrfGuard ssrf) {
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.ssrf = ssrf;
    }

    /** 新建端点并返回 secret 明文（只此一次）。 */
    public Created create(long tenantId, String url, String description, List<String> eventTypes,
                          Integer maxAttempts, Integer deliverQps) {
        String secret = Tokens.newEndpointSecret();
        Endpoint e = new Endpoint();
        e.setTenantId(tenantId);
        e.setUrl(requireAllowedUrl(url));
        e.setDescription(description);
        e.setSecretCiphertext(cipher.encrypt(secret));
        e.setEventTypes(EventTypes.normalizePatterns(eventTypes));
        e.setEnabled(true);
        e.setMaxAttempts(maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts);
        e.setDeliverQps(deliverQps == null ? DEFAULT_DELIVER_QPS : deliverQps);
        endpoints.insert(e);
        return new Created(get(tenantId, e.getId()), secret);
    }

    public Endpoint get(long tenantId, long id) {
        Endpoint e = endpoints.findById(tenantId, id);
        if (e == null) {
            throw ApiException.notFound("endpoint");
        }
        return e;
    }

    public List<Endpoint> list(long tenantId) {
        return endpoints.findByTenant(tenantId);
    }

    /** 部分更新：null 表示不改。 */
    public Endpoint update(long tenantId, long id, String url, String description, List<String> eventTypes,
                           Boolean enabled, Integer maxAttempts, Integer deliverQps) {
        Endpoint e = get(tenantId, id);
        if (url != null) {
            e.setUrl(requireAllowedUrl(url));
        }
        if (description != null) {
            e.setDescription(description);
        }
        if (eventTypes != null) {
            e.setEventTypes(EventTypes.normalizePatterns(eventTypes));
        }
        if (enabled != null) {
            e.setEnabled(enabled);
        }
        if (maxAttempts != null) {
            e.setMaxAttempts(maxAttempts);
        }
        if (deliverQps != null) {
            e.setDeliverQps(deliverQps);
        }
        if (endpoints.update(e) == 0) {
            throw ApiException.notFound("endpoint");
        }
        return get(tenantId, id);
    }

    public String rotateSecret(long tenantId, long id) {
        String secret = Tokens.newEndpointSecret();
        if (endpoints.updateSecret(tenantId, id, cipher.encrypt(secret)) == 0) {
            throw ApiException.notFound("endpoint");
        }
        return secret;
    }

    public void delete(long tenantId, long id) {
        if (endpoints.softDelete(tenantId, id) == 0) {
            throw ApiException.notFound("endpoint");
        }
    }

    /** 语法校验 + SSRF 检查（投递前还会再查一次，见 DeliveryWorker）。 */
    private String requireAllowedUrl(String raw) {
        String url = requireValidUrl(raw);
        SsrfGuard.Decision decision = ssrf.check(url);
        if (!decision.allowed()) {
            throw ApiException.badRequest(decision.retryable() ? "url_unresolvable" : "url_not_allowed",
                    decision.reason());
        }
        return url;
    }

    static String requireValidUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) {
            throw ApiException.badRequest("invalid_url", "url is required and at most 2048 chars");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException ex) {
            throw ApiException.badRequest("invalid_url", "url is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest("invalid_url", "url scheme must be http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.badRequest("invalid_url", "url must have a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw ApiException.badRequest("invalid_url", "url must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            throw ApiException.badRequest("invalid_url", "url must not contain a fragment");
        }
        return uri.toString();
    }

    public record Created(Endpoint endpoint, String secret) {
    }
}
