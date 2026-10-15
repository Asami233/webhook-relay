package party.huantongxue.relay.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.tenant.Tenant;
import party.huantongxue.relay.tenant.TenantLookup;

/**
 * 两类鉴权都只认 {@code Authorization: Bearer <token>}。用拦截器而不是 Servlet Filter：
 * 抛出的 {@link ApiException} 能走统一的 ProblemDetail 错误格式。
 */
public final class AuthInterceptors {

    static final String TENANT_ATTRIBUTE = TenantPrincipal.class.getName();

    private AuthInterceptors() {
    }

    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "missing or invalid credentials");
    }

    /** {@code /api/admin/**}：运维口令，用于创建租户、轮换 API Key。 */
    public static final class Admin implements HandlerInterceptor {

        private final String adminToken;

        public Admin(String adminToken) {
            this.adminToken = adminToken;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!Tokens.constantTimeEquals(bearer(request), adminToken)) {
                throw unauthorized();
            }
            return true;
        }
    }

    /** {@code /api/v1/**}：租户 API Key，按 SHA-256 查（带本地缓存，见 {@link TenantLookup}）。 */
    public static final class ApiKey implements HandlerInterceptor {

        private final TenantLookup tenants;

        public ApiKey(TenantLookup tenants) {
            this.tenants = tenants;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String key = bearer(request);
            if (key == null || !key.startsWith(Tokens.API_KEY_PREFIX)) {
                throw unauthorized();
            }
            Tenant tenant = tenants.findByApiKeyHash(Tokens.sha256Hex(key));
            if (tenant == null) {
                throw unauthorized();
            }
            request.setAttribute(TENANT_ATTRIBUTE,
                    new TenantPrincipal(tenant.getId(), tenant.getName(), tenant.getIngestQps()));
            return true;
        }
    }
}
