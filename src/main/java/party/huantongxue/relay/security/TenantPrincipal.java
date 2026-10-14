package party.huantongxue.relay.security;

/** 由 API Key 解析出的调用方，控制器方法声明此类型参数即可注入。 */
public record TenantPrincipal(long id, String name, int ingestQps) {
}
