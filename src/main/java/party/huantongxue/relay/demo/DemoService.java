package party.huantongxue.relay.demo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.endpoint.Endpoint;
import party.huantongxue.relay.endpoint.EndpointService;
import party.huantongxue.relay.event.EventService;
import party.huantongxue.relay.sink.SinkRegistry;

/**
 * 控制台“一键演示”：建一个按比例失败的模拟接收方并登记为端点，然后在后台写入一批事件，
 * 控制台轮询统计即可实时看到重试、死信与最终成功率。
 *
 * <p>事件在服务端经 {@link EventService} 写入（同一套事务外箱与幂等），不走 HTTP，因此不受接收限流影响；
 * 每次演示用独立的事件类型，新旧演示互不扇出。
 */
@Service
@ConditionalOnProperty(name = "relay.sink.enabled", havingValue = "true")
public class DemoService {

    static final String ENDPOINT_DESCRIPTION = "relay demo";
    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final SinkRegistry sinks;
    private final EndpointService endpoints;
    private final EventService events;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public DemoService(SinkRegistry sinks, EndpointService endpoints, EventService events) {
        this.sinks = sinks;
        this.endpoints = endpoints;
        this.events = events;
    }

    public record Run(String runId, String eventType, String sinkId, long endpointId, int events) {
    }

    public Run start(long tenantId, int count, double failureRate, int latencyMs) {
        if (!running.add(tenantId)) {
            throw new ApiException(HttpStatus.CONFLICT, "demo_running", "a demo is still writing events, try again shortly");
        }
        try {
            retirePreviousDemos(tenantId);
            String runId = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36)
                    + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36), 36);
            String eventType = "demo." + runId + ".order_created";
            SinkRegistry.Sink sink = sinks.create(tenantId, "demo-" + runId,
                    new SinkRegistry.Config(failureRate, 503, latencyMs, latencyMs));
            Endpoint endpoint = endpoints.create(tenantId, sinks.url(sink), ENDPOINT_DESCRIPTION, List.of(eventType),
                    8, 200).endpoint();
            Thread.ofVirtual().name("demo-" + runId).start(() -> {
                try {
                    for (int i = 0; i < count; i++) {
                        events.ingest(tenantId, "demo-" + runId + "-" + i, eventType,
                                "{\"orderId\":\"" + runId + "-" + i + "\",\"amount\":" + (10 + i % 90) + "}");
                    }
                } catch (RuntimeException e) {
                    log.warn("demo {} stopped early: {}", runId, e.getMessage());
                } finally {
                    running.remove(tenantId);
                }
            });
            return new Run(runId, eventType, sink.id(), endpoint.getId(), count);
        } catch (RuntimeException e) {
            running.remove(tenantId);
            throw e;
        }
    }

    /** 旧演示的模拟接收方删掉、端点停用：模拟接收方有数量上限，旧端点也不该继续占用投递资源。 */
    private void retirePreviousDemos(long tenantId) {
        for (SinkRegistry.Sink s : sinks.list(tenantId)) {
            if (s.name().startsWith("demo-")) {
                sinks.delete(tenantId, s.id());
            }
        }
        for (Endpoint e : endpoints.list(tenantId)) {
            if (ENDPOINT_DESCRIPTION.equals(e.getDescription()) && e.isEnabled()) {
                endpoints.update(tenantId, e.getId(), null, null, null, false, null, null);
            }
        }
    }
}
