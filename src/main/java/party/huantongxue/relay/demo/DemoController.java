package party.huantongxue.relay.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import party.huantongxue.relay.security.TenantPrincipal;

@RestController
@ConditionalOnProperty(name = "relay.sink.enabled", havingValue = "true")
public class DemoController {

    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    public record StartDemo(@Min(1) @Max(2000) Integer events,
                            @DecimalMin("0.0") @DecimalMax("1.0") Double failureRate,
                            @Min(0) @Max(2000) Integer latencyMs) {
    }

    /** 202：端点与模拟接收方已建好，事件在后台写入；进度看 /api/v1/stats 与 /api/v1/sinks/{sinkId}。 */
    @PostMapping("/api/v1/demo")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DemoService.Run start(TenantPrincipal tenant, @Valid @RequestBody(required = false) StartDemo request) {
        StartDemo r = request == null ? new StartDemo(null, null, null) : request;
        return demo.start(tenant.id(),
                r.events() == null ? 1000 : r.events(),
                r.failureRate() == null ? 0.3 : r.failureRate(),
                r.latencyMs() == null ? 50 : r.latencyMs());
    }
}
