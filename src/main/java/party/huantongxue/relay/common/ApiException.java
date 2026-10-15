package party.huantongxue.relay.common;

import java.time.Duration;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 业务错误：以 RFC 9457 ProblemDetail 返回，{@code code} 供调用方按机器可读的类别处理。 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** 非空时响应带 Retry-After（429 限流、503 过载）。 */
    private final Duration retryAfter;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Retry-After 只能是整数秒，向上取整，至少 1 秒。 */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
