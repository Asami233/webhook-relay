package party.huantongxue.relay.signature;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 投递签名：{@code X-Relay-Signature: t=<unix 秒>,v1=<hex(HMAC-SHA256(secret, "<t>.<原始请求体>"))>}。
 *
 * <ul>
 *   <li>防伪：只有持有端点 secret 的一方能算出签名。</li>
 *   <li>防重放：时间戳在签名内，接收方拒绝超出容忍窗口（默认 5 分钟）的请求；窗口内的重复投递按事件 ID 去重。</li>
 *   <li>签的是<b>原始字节</b>：接收方必须用收到的原文校验，不能先解析 JSON 再序列化（字段顺序、空格会变）。</li>
 * </ul>
 *
 * <p>本类不依赖 Spring，接收方可以直接复制使用；Python 版见 {@code examples/verify_signature.py}。
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Relay-Signature";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmacHex(secret, timestampSeconds + "." + body);
    }

    /**
     * 校验签名头。header 中可以有多个 v1（为将来轮换 secret 时新旧并行留余地），任一匹配即通过。
     *
     * @param nowSeconds 当前时间（unix 秒），由调用方传入便于测试
     */
    public static boolean verify(String secret, String header, String body, long nowSeconds, Duration tolerance) {
        if (secret == null || header == null || body == null) {
            return false;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        if (Math.abs(nowSeconds - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        byte[] expected = hmacHex(secret, timestamp + "." + body).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            // 常量时间比较，避免按响应时间逐字节猜出签名
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
