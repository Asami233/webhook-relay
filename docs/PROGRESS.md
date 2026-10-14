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
- 线上：已推送 github.com/Asami233/webhook-relay（公开），CI 首次运行通过（b1487e5）。

## D2 投递引擎 — 完成（2026-10-14）

- 必做全部完成：`SKIP LOCKED` 抢任务（`DeliveryStore.claim`，抢与改 IN_FLIGHT 同一短事务，提交后才发 HTTP）、租约、虚拟线程投递（`DeliveryWorker`）、指数退避 + equal jitter 并尊重 `Retry-After`（`RetryPolicy`）、死信、过期租约清扫、优雅停机（`SmartLifecycle`：停抢 → 等在途写回）。
- 应做完成：单端点并发上限（信号量；等待超时则退回 PENDING 并撤销计数，避免租约在途中过期）。
- 额外：回写结果以 `attempts` 作 fencing token，租约过期后旧实例的迟到结果更新 0 行；尝试记录仍保留作审计。
- 验收（测试证明）：`DeliveryEngineIntegrationTest` 9 项——4 个并发抢任务者 200 行无重复；**两个 worker 同时跑 300 个事件，每个恰好送达一次且两者都参与**；503×2 后成功；400 直接死信；超过次数死信；连接失败记录并重试；停用端点不发送直接死信；租约过期清扫 + fencing；优雅停机等在途完成。连续重复运行 3 次均通过。`./mvnw verify` 共 28 项通过。
- 真实运行：Compose + `spring-boot:run`，本机 Python 接收方 30% 返回 503，50 个事件 → 49 成功（共 70 次 HTTP）、1 个连续 4 次失败进死信；另有 2 次是接收方 backlog 满导致连接被拒，被正确判为可重试并最终成功。
- 修复的问题：
  - 测试中 `@ServiceConnection` 会整体替换 JDBC URL，写在 URL 里的 `connectionTimeZone=UTC` 被丢掉，Java 写入的时间按 JVM 时区（+09:00）存储。改为放进 `spring.datasource.hikari.data-source-properties`，测试与生产一致（测试里用 `started_at` 与 `NOW(3)` 比较锁住此问题）。
  - 连接被拒时错误信息为 `connect failed: null`，改为沿 cause 链取描述。
- 未做/限制：人工重放（DEAD → PENDING）需要在不破坏 `(delivery_id, attempt_no)` 唯一约束的前提下追加尝试预算，放到 D6 控制台一起做（需新迁移）；在 Windows 上 `Stop-Process` 是硬杀，真实进程的优雅停机只由测试覆盖。

## D3 幂等·限流·签名 — 未开始

Redis 幂等快速路径 + 唯一索引兜底、Lua 令牌桶（接收侧）、HMAC 签名 + 校验示例、SSRF 防护（登记与投递两处；测试用接收方在 127.0.0.1，需要显式的允许内网开关，默认关闭）；应做：投递侧限流、端点熔断。
