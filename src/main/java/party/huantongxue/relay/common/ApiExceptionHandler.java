package party.huantongxue.relay.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 继承 Spring 的默认处理（参数校验、缺少请求头等也返回 ProblemDetail），再补上业务异常。 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        body.setProperty("code", e.getCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
