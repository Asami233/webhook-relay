package party.huantongxue.relay.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 启动时校验：缺少管理口令或主密钥直接启动失败，不带着空密钥跑。
 *
 * @param adminToken      管理接口口令（创建租户等），至少 16 字符
 * @param masterKey       Base64 编码的 32 字节 AES 密钥，用于加密端点 secret
 * @param maxPayloadBytes 单个事件 payload 序列化后的上限
 */
@Validated
@ConfigurationProperties("relay")
public record RelayProperties(
        @NotBlank @Size(min = 16) String adminToken,
        @NotBlank String masterKey,
        @Min(1) int maxPayloadBytes) {
}
