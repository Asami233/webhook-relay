package party.huantongxue.relay.signature;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":1,\"type\":\"order.created\",\"data\":{\"a\":1}}";
    private static final long T = 1_760_000_000L;
    private static final Duration TOLERANCE = WebhookSignature.DEFAULT_TOLERANCE;

    @Test
    void knownVectorMatchesPythonExample() {
        // 与 examples/verify_signature.py 的自检向量一致；两边实现必须算出同一个值
        assertThat(WebhookSignature.sign(SECRET, T, BODY))
                .isEqualTo("t=1760000000,v1=" + PythonVector.EXPECTED_HEX);
    }

    @Test
    void verifiesOwnSignatureWithinWindow() {
        String header = WebhookSignature.sign(SECRET, T, BODY);
        assertThat(WebhookSignature.verify(SECRET, header, BODY, T, TOLERANCE)).isTrue();
        assertThat(WebhookSignature.verify(SECRET, header, BODY, T + 299, TOLERANCE)).isTrue();
    }

    @Test
    void rejectsTamperingWrongSecretAndStaleTimestamp() {
        String header = WebhookSignature.sign(SECRET, T, BODY);
        assertThat(WebhookSignature.verify(SECRET, header, BODY.replace("1}", "2}"), T, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify("whsec_other", header, BODY, T, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, header, BODY, T + 301, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, header, BODY, T - 301, TOLERANCE)).isFalse();
        // 篡改时间戳但保留原签名：签名覆盖了时间戳，因此失败
        assertThat(WebhookSignature.verify(SECRET, header.replace("t=" + T, "t=" + (T + 10)), BODY, T, TOLERANCE))
                .isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(WebhookSignature.verify(SECRET, "", BODY, T, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "v1=abc", BODY, T, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=abc", BODY, T, TOLERANCE)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, null, BODY, T, TOLERANCE)).isFalse();
    }

    @Test
    void acceptsAnyOfMultipleSignatures() {
        String good = WebhookSignature.sign(SECRET, T, BODY);
        String header = "t=" + T + ",v1=deadbeef," + good.substring(good.indexOf("v1="));
        assertThat(WebhookSignature.verify(SECRET, header, BODY, T, TOLERANCE)).isTrue();
    }

    /** 由 examples/verify_signature.py --self-test 打印，独立于 Java 实现得到。 */
    private static final class PythonVector {
        static final String EXPECTED_HEX = "895a391a24f5cfb53175f18172ba649bfb162b9a3a92cc80d1a14e144366208d";
    }
}
