# Relay · Webhook 可靠投递平台

业务系统把事件交给 Relay，Relay 负责把它**可靠地**推送到订阅方的 HTTP 地址：不丢、不重复接收、可重试、可追踪、可限流、签名防伪。

> 开发中（第 1 周 7 天计划，见 [docs/PLAN.md](docs/PLAN.md)；进度见 [docs/PROGRESS.md](docs/PROGRESS.md)）。完整架构图与 API 文档在 D7 补齐。

## 技术栈

Java 21 · Spring Boot 4.1 · MySQL 8.4 · MyBatis · Flyway · Redis 7 · Testcontainers · Micrometer · Vue 3 · Docker Compose · GitHub Actions · k6

## 本地运行

需要 JDK 21 与 Docker。

```bash
cp .env.example .env        # 填入随机口令；RELAY_MASTER_KEY 用 openssl rand -base64 32
docker compose up -d --wait # MySQL(127.0.0.1:13306) + Redis
./mvnw spring-boot:run      # http://localhost:8080，启动时 Flyway 自动建表
```

控制台（Vue 3）：

```bash
cd console && npm ci && npm run build   # 产出 console/dist，由后端在 http://localhost:8080/console/ 提供
npm run dev                             # 或开发模式：http://localhost:5173/console/，API 代理到 8080
```

用租户 API Key 登录；“演示”页一键生成 1000 个事件打到 30% 失败率的模拟接收方，实时看重试、死信和送达率。

也可以把 Relay 本身放进容器（CPU/内存上限模拟 1 核 1G VPS，参数依据见 [docs/BENCHMARK.md](docs/BENCHMARK.md)）：

```bash
./mvnw -DskipTests package
docker compose --profile app up -d --build --force-recreate --wait
```

测试（Testcontainers 自动拉起 MySQL，需要 Docker 在运行）：

```bash
./mvnw verify
```

## API 速览

所有接口用 `Authorization: Bearer <token>`；错误统一为 RFC 9457 ProblemDetail，`code` 字段是机器可读类别。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/admin/tenants` | 管理口令 | 创建租户，**API Key 明文只返回这一次** |
| GET | `/api/admin/tenants` | 管理口令 | 租户列表 |
| POST | `/api/admin/tenants/{id}/rotate-key` | 管理口令 | 轮换 API Key |
| POST | `/api/v1/endpoints` | API Key | 登记端点；`eventTypes` 支持 `*`、`order.*`、精确类型；返回签名 secret（只此一次） |
| GET / PATCH / DELETE | `/api/v1/endpoints/{id}` | API Key | 查看 / 部分更新 / 软删除 |
| POST | `/api/v1/endpoints/{id}/rotate-secret` | API Key | 轮换签名 secret |
| POST | `/api/v1/events` | API Key + `Idempotency-Key` 头 | 接收事件，返回 202；重复键返回首次结果并带 `Idempotent-Replayed: true`；同键不同内容 422 |
| GET | `/api/v1/events?before=&limit=` | API Key | 事件列表（键集分页） |
| GET | `/api/v1/events/{id}` | API Key | 事件详情与各端点投递状态 |
| GET | `/api/v1/deliveries?status=&before=&limit=` | API Key | 投递列表（如 `status=DEAD` 查死信） |
| GET | `/api/v1/deliveries/{id}` | API Key | 投递详情与每次尝试（状态码、耗时、错误、响应前 1 KB） |
| POST | `/api/v1/deliveries/{id}/replay` | API Key | 死信重放：本轮重新获得 `maxAttempts` 次机会，尝试历史保留 |
| POST | `/api/v1/endpoints/{id}/replay-dead` | API Key | 接收方修好后批量重放该端点全部死信 |
| GET | `/api/v1/stats` | API Key | 概览：按状态的投递数、最近 60 分钟每分钟成功/失败尝试 |
| POST | `/api/v1/demo` | API Key | 一键演示（需 `RELAY_SINK_ENABLED=true`）：建模拟接收方与端点并在后台写入事件 |
| POST / GET / PUT / DELETE | `/api/v1/sinks[/{id}[/config]]` | API Key | 模拟接收方（需 `RELAY_SINK_ENABLED=true`）：失败率、失败状态码、延迟；统计重复投递 |

```bash
curl -X POST localhost:8080/api/v1/events \
  -H "Authorization: Bearer $API_KEY" -H "Idempotency-Key: order-1001-created" \
  -H "Content-Type: application/json" \
  -d '{"type":"order.created","payload":{"orderId":1001}}'
# 202 {"eventId":1,"deliveries":1}
```

## 可观测性

- `GET /actuator/prometheus`：`relay_delivery_total{result}`、`relay_delivery_latency_seconds`（事件创建 → 成功送达，直方图）、`relay_delivery_attempt_seconds{outcome}`（单次 HTTP）、`relay_backlog`、`relay_oldest_pending_seconds`、`relay_deliveries{status}`、`relay_delivery_deferred_total{reason}`、`relay_ingest_total{result}`。标签只用低基数类别，不按租户/端点打标签。
- 积压类指标由定时任务每 5 秒查库刷新，抓取只读内存值。
- `/actuator/health/liveness`、`/actuator/health/readiness`（就绪要求数据库可用；Redis 故障时 fail-open，不影响就绪）。
- 投递相关日志带 `[delivery=… event=…]`。
- 本地面板：`docker compose --profile monitoring up -d`，Grafana <http://127.0.0.1:3000>（匿名只读，已预置 Webhook Relay 面板），Prometheus <http://127.0.0.1:9091>。

## 性能（1 核容器，详见 [docs/BENCHMARK.md](docs/BENCHMARK.md)）

接收 600 次/秒时 P99 48 ms；过载时超出部分立即 503（不雪崩）；应用内存约 300 MiB（`-Xmx128m` Serial）。调优过程：连接池瓶颈定位（等待 662 ms vs 占用 12 ms）、过载时 GC 雪崩、投递侧限流的惊群与令牌预约、单核下 Serial vs G1。

## 设计要点（已实现部分）

- **事务外箱**：`event` 与扇出的 `delivery` 在同一事务写入；返回 202 即代表投递任务已持久化。
- **幂等**：`UNIQUE(tenant_id, idempotency_key)` 兜底；并发同键请求只有一个插入成功，其余读回已提交结果。另存请求摘要，同键不同内容判为冲突。
- **密钥**：API Key 为 256 位随机数，只存 SHA-256；端点 secret 需要明文签名，用 AES-256-GCM 加密存储，主密钥来自环境变量。
- **租户隔离**：所有查询带 `tenant_id`，跨租户访问一律 404。
- **抢任务**：`SELECT … FOR UPDATE SKIP LOCKED` 与改为 `IN_FLIGHT`、设租约在同一短事务；提交后才发 HTTP，网络请求不占行锁和连接。多实例无需互相通信。
- **租约与 fencing**：实例崩溃后租约过期，清扫任务退回 `PENDING`；回写结果带 `attempts` 条件，旧实例的迟到结果不会覆盖新一轮投递。
- **重试**：2xx 成功；网络错误、超时、408、429、5xx 按指数退避 + 抖动重试（尊重 `Retry-After`）；其余 4xx/3xx 直接死信；每次尝试写 `delivery_attempt`（响应体截断 1 KB）。
- **并发**：虚拟线程执行投递，用信号量限制全局与单端点在途数，而不是靠线程池大小。
- **至少一次**：每次请求带 `X-Relay-Event-Id`、`X-Relay-Delivery-Id`、`X-Relay-Attempt`，接收方按事件 ID 去重。
- **幂等快速路径**：事务提交后把结果写入 Redis（24 h）；重复请求在 Redis 命中直接返回，未命中或 Redis 故障回落数据库。正确性只依赖唯一索引。
- **限流**：Redis + Lua 令牌桶（时间取 Redis `TIME`，多实例共享）。接收侧按租户 `ingestQps`，超限 429 + `Retry-After`；投递侧按端点 `deliverQps`，等待 ≤ 1 秒时**预约令牌**原地等（避免等待者同时醒来再抢的惊群），更久才退回队列且不计尝试次数。Redis 不可用时放行（fail-open）。
- **过载保护**：接收接口并发上限（默认 64），超出立即 503 + `Retry-After`，防止排队撑满堆、GC 停顿拉长连接占用导致雪崩。
- **熔断**：端点连续可重试失败达到阈值（默认 10 次）后暂停 60 秒；冷却后再失败一次立即重新打开。
- **SSRF 防护**：登记时与每次投递前都解析域名，任一解析结果落在环回、私网、链路本地（含云元数据 169.254.169.254）、CGNAT、组播、IPv6 ULA 及其 IPv4 嵌入形式即拒绝；不跟随重定向。已知局限：检查与建连之间的 DNS rebinding 窗口（彻底方案是固定已校验 IP 或使用出口代理）。

## 接收方：校验签名

每次投递带 `X-Relay-Signature: t=<unix 秒>,v1=<hex(HMAC-SHA256(secret, "<t>.<原始请求体>"))>`，`secret` 是登记端点时返回的 `whsec_…`。

1. 用**原始请求体字节**计算 HMAC，不要先解析 JSON 再序列化；
2. 拒绝时间戳与当前时间相差超过 5 分钟的请求（防重放）；
3. 常量时间比较签名；`v1` 可能有多个，任一匹配即通过；
4. 投递是至少一次：校验通过后按 `X-Relay-Event-Id` 去重再处理。

- Java：[`WebhookSignature.verify(...)`](src/main/java/party/huantongxue/relay/signature/WebhookSignature.java)（无 Spring 依赖，可直接复制）
- Python：[`examples/verify_signature.py`](examples/verify_signature.py)（仅标准库；两种实现用同一组测试向量互相校验）
