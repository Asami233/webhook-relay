# 进度

计划见 [PLAN.md](PLAN.md)。每天结束：`./mvnw verify` 全绿，本表更新验收结果。

## 已定决策

- **2026-10-14**：代码为独立公开仓库（本目录），不放入私有的 `huantongxue` 仓库；个人网站 `site/` 只加项目卡片与案例文章（D7）。
- **2026-10-14**：演示子域名建议用 `webhook.huantongxue.party`，避免与门户里已有的 AI 中转（`api.` / `portal/relay/`）混淆；D7 部署时确认。
- 版本（2026-10-14 取自 Maven Central）：Spring Boot 4.1.1（Jackson 3、Flyway 12.4、MySQL Connector 9.7、Testcontainers 2.0.5），MyBatis Starter 4.1.0。
- 本机已有服务占用 3306，Compose 的 MySQL 映射到 **3307**。
- 鉴权不用 Spring Security：两个 `HandlerInterceptor`（管理口令 / 租户 API Key）足够，错误能走统一 ProblemDetail。
- 隔离级别 READ COMMITTED（Hikari 与 Compose 均设置）。

## D1 骨架与接收 — 完成（2026-10-14）

- 必做：项目结构、Compose（MySQL/Redis）、Flyway V1 五张表、租户/端点 CRUD、`POST /api/v1/events` + 事务外箱、CI（`.github/workflows/ci.yml`）——全部完成。
- 应做：API Key 鉴权——完成（SHA-256 存储；端点 secret AES-GCM 加密）。
- 验收：
  - `./mvnw verify`：15 项通过（单元 8、Testcontainers 集成 7，含 8 线程并发同键只产生 1 个事件）。
  - 真实运行：`docker compose up` + `spring-boot:run`，curl 发事件 → 202，库中 `event` 1 行、`delivery` 1 行 `PENDING`；重放返回 `Idempotent-Replayed: true`；同键不同内容 422。
- 未验证：CI 尚未在 GitHub 上运行（仓库未创建/推送）。

## D2 投递引擎 — 未开始

`SKIP LOCKED` 抢任务、租约、虚拟线程投递、指数退避 + 抖动、死信、过期租约清扫、优雅停机；应做：单端点并发上限。验收：两个实例同时跑不重复投递（测试证明）。
