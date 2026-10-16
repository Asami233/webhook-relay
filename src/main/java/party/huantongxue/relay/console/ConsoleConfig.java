package party.huantongxue.relay.console;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 控制台静态文件：Vite 构建产物（{@code console/dist}）由本服务在 {@code /console/} 下提供，同源调用 API，
 * 不需要 CORS。目录由 {@code relay.console.dir} 指定（镜像里为 /app/console）；目录不存在时只是 404。
 * 前端用 hash 路由，因此不需要把任意路径回退到 index.html。
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleConfig implements WebMvcConfigurer {

    /**
     * 控制台把 API Key 存在 sessionStorage：一旦有 XSS 就可能被读走。CSP 只允许同源脚本、禁止内联脚本与外部连接，
     * 把这类风险压到最低（Vue 的 :style 绑定需要内联样式）。
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final String dir;

    public ConsoleConfig(@Value("${relay.console.dir:console/dist}") String dir) {
        this.dir = dir.endsWith("/") ? dir : dir + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/**").addResourceLocations("file:" + dir);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/console/");
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> consoleSecurityHeaders() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                response.setHeader("Content-Security-Policy", CSP);
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                chain.doFilter(request, response);
            }
        });
        bean.addUrlPatterns("/console/*");
        return bean;
    }
}
