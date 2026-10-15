package party.huantongxue.relay.event;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import party.huantongxue.relay.common.ApiException;

/**
 * 接收接口的并发上限（过载保护 / load shedding）。
 *
 * <p>D5 压测：超过容量后请求在连接池前排队，几百个在途请求撑满小堆，GC 停顿发生在持有数据库连接期间，
 * 连接占用时间被拉长 → 排队更多，形成过载崩溃（P50 从 25 ms 升到 1.5 s）。限制同时处理的请求数后，
 * 超出部分立即得到 503 + Retry-After，已接收请求的延迟和内存保持稳定。客户端带幂等键重试是安全的。
 *
 * <p>与按租户限流（429）的区别：限流是公平性策略，按租户配额；这里是保护服务本身，按实例容量。
 */
@Component
public class IngestAdmission {

    private final Semaphore permits;

    public IngestAdmission(@Value("${relay.ingest.max-concurrent:64}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("relay.ingest.max-concurrent must be >= 1");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    /** 拿不到许可立即抛 503，不排队。成功后必须在 finally 里 {@link #exit()}。 */
    public void enter() {
        if (!permits.tryAcquire()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "overloaded",
                    "server is at capacity, retry later with the same Idempotency-Key", Duration.ofSeconds(1));
        }
    }

    public void exit() {
        permits.release();
    }
}
