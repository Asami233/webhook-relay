package party.huantongxue.relay.sink;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.security.TenantPrincipal;

/**
 * 内置模拟接收方（演示用，{@code relay.sink.enabled=true} 时才存在）：
 * 租户通过 {@code /api/v1/sinks} 创建并配置失败率、状态码、延迟，再把返回的地址登记为端点；
 * 投递打到公开的 {@code POST /sink/{id}}，可实时看到重试、死信和重复投递统计。
 */
@RestController
@ConditionalOnProperty(name = "relay.sink.enabled", havingValue = "true")
public class SinkController {

    private final SinkRegistry registry;

    public SinkController(SinkRegistry registry) {
        this.registry = registry;
    }

    public record SinkConfig(@DecimalMin("0.0") @DecimalMax("1.0") Double failureRate,
                             @Min(400) @Max(599) Integer failureStatus,
                             @Min(0) @Max(20_000) Integer latencyMs,
                             @Min(0) @Max(10_000) Integer jitterMs) {

        SinkRegistry.Config toConfig(SinkRegistry.Config base) {
            return new SinkRegistry.Config(
                    failureRate != null ? failureRate : base.failureRate(),
                    failureStatus != null ? failureStatus : base.failureStatus(),
                    latencyMs != null ? latencyMs : base.latencyMs(),
                    jitterMs != null ? jitterMs : base.jitterMs());
        }
    }

    public record CreateSink(@NotBlank @Size(max = 64) String name, @Valid SinkConfig config) {
    }

    public record SinkView(String id, String name, String url, SinkRegistry.Config config, SinkRegistry.Stats stats,
                           Instant createdAt) {
    }

    private static final SinkRegistry.Config DEFAULT = new SinkRegistry.Config(0.0, 503, 0, 0);

    @PostMapping("/api/v1/sinks")
    @ResponseStatus(HttpStatus.CREATED)
    public SinkView create(TenantPrincipal tenant, @Valid @RequestBody CreateSink request) {
        SinkRegistry.Config config = request.config() == null ? DEFAULT : request.config().toConfig(DEFAULT);
        return view(registry.create(tenant.id(), request.name().trim(), config));
    }

    @GetMapping("/api/v1/sinks")
    public List<SinkView> list(TenantPrincipal tenant) {
        return registry.list(tenant.id()).stream().map(this::view).toList();
    }

    @GetMapping("/api/v1/sinks/{id}")
    public SinkView get(TenantPrincipal tenant, @PathVariable String id) {
        return view(registry.require(tenant.id(), id));
    }

    /** 部分更新配置（演示中途把失败率调到 0，观察积压被重试清空）。 */
    @PutMapping("/api/v1/sinks/{id}/config")
    public SinkView configure(TenantPrincipal tenant, @PathVariable String id, @Valid @RequestBody SinkConfig request) {
        SinkRegistry.Sink sink = registry.require(tenant.id(), id);
        sink.config = request.toConfig(sink.config);
        return view(sink);
    }

    @DeleteMapping("/api/v1/sinks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(TenantPrincipal tenant, @PathVariable String id) {
        registry.delete(tenant.id(), id);
    }

    /** 公开接收入口：不鉴权（它扮演的就是第三方接收方），只凭 128 位随机 ID 定位。 */
    @PostMapping("/sink/{id}")
    public ResponseEntity<String> receive(@PathVariable String id,
                                          @RequestHeader(value = "X-Relay-Delivery-Id", required = false) String deliveryId)
            throws InterruptedException {
        SinkRegistry.Sink sink = registry.find(id);
        if (sink == null) {
            throw ApiException.notFound("sink");
        }
        SinkRegistry.Config c = sink.config;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long delay = c.latencyMs() + (c.jitterMs() > 0 ? random.nextInt(c.jitterMs() + 1) : 0);
        if (delay > 0) {
            Thread.sleep(delay); // 运行在虚拟线程上，等待不占平台线程
        }
        boolean fail = random.nextDouble() < c.failureRate();
        sink.record(deliveryId, fail);
        return fail
                ? ResponseEntity.status(c.failureStatus()).body("simulated failure")
                : ResponseEntity.ok("ok");
    }

    private SinkView view(SinkRegistry.Sink s) {
        return new SinkView(s.id(), s.name(), registry.url(s), s.config(), s.stats(), s.createdAt());
    }
}
