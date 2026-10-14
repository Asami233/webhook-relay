package party.huantongxue.relay.delivery;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * 本机 HTTP 接收方：记录每个请求，按“该投递第几次到达”决定返回码。
 */
final class TestReceiver implements AutoCloseable {

    record Received(long deliveryId, int attempt, String body) {
    }

    private final HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger started = new AtomicInteger();
    private final Map<Long, AtomicInteger> arrivals = new ConcurrentHashMap<>();
    private volatile IntUnaryOperator statusForArrival = n -> 200;
    private volatile Duration delay = Duration.ZERO;

    TestReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/hook", exchange -> {
            started.incrementAndGet();
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                long deliveryId = Long.parseLong(exchange.getRequestHeaders().getFirst("X-Relay-Delivery-Id"));
                int attempt = Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Relay-Attempt"));
                int arrival = arrivals.computeIfAbsent(deliveryId, k -> new AtomicInteger()).incrementAndGet();
                if (!delay.isZero()) {
                    Thread.sleep(delay);
                }
                int status = statusForArrival.applyAsInt(arrival);
                received.add(new Received(deliveryId, attempt, body));
                byte[] out = ("status " + status).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    /** 第 n 次到达（同一 delivery）时返回的状态码。 */
    TestReceiver respond(IntUnaryOperator statusForArrival) {
        this.statusForArrival = statusForArrival;
        return this;
    }

    TestReceiver delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
