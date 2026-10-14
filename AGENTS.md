# 开发规则

Webhook 可靠投递平台（Spring Boot 4 + MySQL + MyBatis + Redis）。接手先读 `docs/PROGRESS.md`（当前进度）与 `docs/PLAN.md`（总计划）；进度只写入 `docs/PROGRESS.md`。用户当次指令优先。

- 公开仓库：不提交 `.env`、真实口令、主密钥、API Key；示例配置只放 `.env.example`，测试固定值只放 `src/test/resources/application-test.yml`。
- 关键 SQL 写在 `src/main/resources/mapper/*.xml`；表结构只通过新增 Flyway 迁移修改，不改已发布的迁移文件。
- 网络请求不放进数据库事务；涉及租户数据的查询必须带 `tenant_id`。
- 完成前跑 `./mvnw verify`（需要 Docker）；区分“测试通过”“本地真实运行”“线上验证”。
- 未经用户同意不提交、不推送；个人网站（`D:\java\vps\huantongxue`）与服务器的改动按该仓库自己的规则逐次授权。
