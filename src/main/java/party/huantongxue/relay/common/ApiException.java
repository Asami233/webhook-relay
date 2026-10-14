package party.huantongxue.relay.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 业务错误：以 RFC 9457 ProblemDetail 返回，{@code code} 供调用方按机器可读的类别处理。 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
