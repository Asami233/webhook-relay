package party.huantongxue.relay.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * 发出一次 HTTP 投递。阻塞式调用，运行在虚拟线程上：等待网络时不占平台线程，几百个在途请求也只需少量载体线程。
 *
 * <p>不跟随重定向：3xx 按永久失败处理，避免被引导到内网地址（SSRF 校验见 D3）。
 */
public class DeliverySender {

    static final int SNIPPET_BYTES = 1024;
    static final String USER_AGENT = "webhook-relay/0.1";

    private final HttpClient client;
    private final Duration requestTimeout;

    public DeliverySender(DeliveryProperties props) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.requestTimeout = props.requestTimeout();
    }

    /** 一次尝试的结果；statusCode 为 null 表示没拿到 HTTP 响应（网络错误/超时）。 */
    public record Result(Instant startedAt, int durationMs, Integer statusCode, String error, String snippet,
                         Duration retryAfter) {
    }

    public Result send(ClaimedDelivery d) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(d.getUrl()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Relay-Event-Id", Long.toString(d.getEventId()))
                .header("X-Relay-Event-Type", d.getEventType())
                .header("X-Relay-Delivery-Id", Long.toString(d.getId()))
                .header("X-Relay-Attempt", Integer.toString(d.getAttempts()))
                .POST(HttpRequest.BodyPublishers.ofString(body(d), StandardCharsets.UTF_8))
                .build();

        Instant started = Instant.now();
        long t0 = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String snippet;
            try (InputStream in = response.body()) {
                // 只读前 1 KB 留档；关闭流即放弃剩余响应体，大响应不会拖慢 worker
                snippet = new String(in.readNBytes(SNIPPET_BYTES), StandardCharsets.UTF_8);
            }
            Duration retryAfter = RetryPolicy.parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null));
            return new Result(started, elapsedMs(t0), response.statusCode(), null, snippet, retryAfter);
        } catch (HttpConnectTimeoutException e) {
            return failure(started, t0, "connect timeout");
        } catch (HttpTimeoutException e) {
            return failure(started, t0, "request timeout");
        } catch (ConnectException e) {
            return failure(started, t0, "connect failed: " + describe(e));
        } catch (IOException e) {
            return failure(started, t0, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(started, t0, "interrupted (shutdown)");
        }
    }

    /**
     * 投递体：事件元数据 + 原始 payload。type 已按 [A-Za-z0-9_.-] 校验、payload 是数据库里的合法 JSON，
     * 直接拼接无需转义。
     */
    static String body(ClaimedDelivery d) {
        return "{\"id\":" + d.getEventId()
                + ",\"type\":\"" + d.getEventType() + "\""
                + ",\"createdAt\":\"" + d.getEventCreatedAt() + "\""
                + ",\"data\":" + d.getPayload() + "}";
    }

    /** HttpClient 的异常消息经常为 null（如连接被拒），沿 cause 链找第一个有用的描述。 */
    static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        return e.getClass().getSimpleName();
    }

    private static Result failure(Instant started, long t0, String error) {
        return new Result(started, elapsedMs(t0), null, error, null, null);
    }

    private static int elapsedMs(long t0) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
    }
}
