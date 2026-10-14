package party.huantongxue.relay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API Key 与端点 secret 的生成和哈希。
 *
 * <p>API Key 是 256 位随机数，不是用户口令，因此用 SHA-256 存储即可（无法字典攻击），
 * 也能按哈希直接建唯一索引查找；bcrypt 这类慢哈希只对低熵口令有意义。
 */
public final class Tokens {

    public static final String API_KEY_PREFIX = "rk_";
    public static final String SECRET_PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    public static String newApiKey() {
        return API_KEY_PREFIX + randomBase64Url(32);
    }

    public static String newEndpointSecret() {
        return SECRET_PREFIX + randomBase64Url(32);
    }

    /** 列表里展示的可识别前缀，例如 {@code rk_AbC12x}。 */
    public static String displayPrefix(String apiKey) {
        return apiKey.substring(0, Math.min(apiKey.length(), API_KEY_PREFIX.length() + 6));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 常量时间比较，避免按响应时间逐字节猜口令。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomBase64Url(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
