package party.huantongxue.relay.event;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import party.huantongxue.relay.common.ApiException;
import party.huantongxue.relay.config.RelayProperties;
import party.huantongxue.relay.delivery.DeliveryMapper;
import party.huantongxue.relay.endpoint.Endpoint;
import party.huantongxue.relay.endpoint.EndpointMapper;
import party.huantongxue.relay.endpoint.EventTypes;
import party.huantongxue.relay.security.Tokens;

/**
 * 接收事件：同一事务里写 event 并按订阅扇出 delivery（事务外箱）。只要 202 返回，投递任务就已持久化；
 * 事务失败则两者都不存在，不会出现“事件在、任务丢”的中间态。
 *
 * <p>幂等：UNIQUE(tenant_id, idempotency_key) 兜底。并发的同键请求里只有一个能插入，
 * 其余在 InnoDB 唯一索引上等待对方提交后得到 DuplicateKeyException，再在<b>新的</b>语句里读出已提交的结果返回。
 * 前面有 Redis 快速路径（{@link IdempotencyCache}），但正确性只依赖这里的唯一索引。
 */
@Service
public class EventService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final EventMapper events;
    private final EndpointMapper endpoints;
    private final DeliveryMapper deliveries;
    private final TransactionTemplate tx;
    private final IdempotencyCache cache;
    private final int maxPayloadBytes;

    public EventService(EventMapper events, EndpointMapper endpoints, DeliveryMapper deliveries,
                        TransactionTemplate tx, IdempotencyCache cache, RelayProperties properties) {
        this.events = events;
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.tx = tx;
        this.cache = cache;
        this.maxPayloadBytes = properties.maxPayloadBytes();
    }

    public record IngestResult(long eventId, int deliveries, boolean replayed) {
    }

    public IngestResult ingest(long tenantId, String idempotencyKey, String type, String payloadJson) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("invalid_idempotency_key",
                    "Idempotency-Key header must be 1-255 printable ASCII characters");
        }
        EventTypes.requireValidType(type);
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                    "payload exceeds " + maxPayloadBytes + " bytes");
        }
        String requestHash = Tokens.sha256Hex(type + "\n" + payloadJson);

        Optional<IdempotencyCache.Entry> cached = cache.get(tenantId, idempotencyKey);
        if (cached.isPresent()) {
            IdempotencyCache.Entry hit = cached.get();
            requireSameRequest(hit.requestHash(), requestHash);
            return new IngestResult(hit.eventId(), hit.deliveries(), true);
        }

        IngestResult result;
        try {
            // TransactionTemplate 而非 @Transactional：事务边界一目了然，也不受同类自调用不生效的影响
            result = tx.execute(status -> insertWithFanOut(tenantId, idempotencyKey, requestHash, type, payloadJson));
        } catch (DuplicateKeyException duplicate) {
            result = replay(tenantId, idempotencyKey, requestHash);
        }
        // 走到这里事务已提交（或读到的是别人已提交的行），可以安全写缓存
        cache.put(tenantId, idempotencyKey,
                new IdempotencyCache.Entry(result.eventId(), result.deliveries(), requestHash));
        return result;
    }

    private IngestResult insertWithFanOut(long tenantId, String key, String requestHash, String type, String payload) {
        Event event = new Event();
        event.setTenantId(tenantId);
        event.setIdempotencyKey(key);
        event.setRequestHash(requestHash);
        event.setType(type);
        event.setPayload(payload);
        events.insert(event);

        List<Long> targets = endpoints.findEnabledByTenant(tenantId).stream()
                .filter(e -> EventTypes.matches(e.getEventTypes(), type))
                .map(Endpoint::getId)
                .toList();
        if (!targets.isEmpty()) {
            deliveries.insertPending(tenantId, event.getId(), targets);
        }
        return new IngestResult(event.getId(), targets.size(), false);
    }

    private IngestResult replay(long tenantId, String key, String requestHash) {
        Event existing = events.findByIdempotencyKey(tenantId, key);
        if (existing == null) {
            // 理论上不会发生：拿到唯一键冲突说明对方已提交。保守地让客户端重试。
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_retry", "concurrent request in progress, retry");
        }
        requireSameRequest(existing.getRequestHash(), requestHash);
        return new IngestResult(existing.getId(), deliveries.countByEvent(existing.getId()), true);
    }

    private static void requireSameRequest(String storedHash, String requestHash) {
        if (!storedHash.equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "Idempotency-Key was already used with a different request body");
        }
    }
}
