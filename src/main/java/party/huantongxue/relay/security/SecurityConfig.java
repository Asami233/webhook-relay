package party.huantongxue.relay.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import party.huantongxue.relay.config.RelayProperties;
import party.huantongxue.relay.tenant.TenantLookup;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig implements WebMvcConfigurer {

    private final RelayProperties properties;
    private final TenantLookup tenants;

    public SecurityConfig(RelayProperties properties, TenantLookup tenants) {
        this.properties = properties;
        this.tenants = tenants;
    }

    @Bean
    SecretCipher secretCipher() {
        return new SecretCipher(properties.masterKey());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptors.Admin(properties.adminToken())).addPathPatterns("/api/admin/**");
        registry.addInterceptor(new AuthInterceptors.ApiKey(tenants)).addPathPatterns("/api/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == TenantPrincipal.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                Object principal = request.getAttribute(AuthInterceptors.TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (principal == null) {
                    // 控制器声明了租户参数，却没挂在 /api/v1/** 下：属于编码错误，不应静默放行
                    throw new IllegalStateException("TenantPrincipal requested outside /api/v1/**");
                }
                return principal;
            }
        });
    }
}
