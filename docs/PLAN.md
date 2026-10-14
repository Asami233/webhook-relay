# Relay：Webhook 可靠投递平台 · 开发计划

2026-10-13 起草。总周期两周：**第 1 周开发（7 天），第 2 周项目理解与八股准备**。目标岗位：Java 后端 / 全栈 / 运维。

## 1. 一句话与卖点

业务系统把事件交给 Relay，Relay 负责把它**可靠地**推送到订阅方的 HTTP 地址：不丢、不重复、可重试、可追踪、可限流、签名防伪。

面试叙事：先在个人门户（Django）里解决过“幂等任务队列 + 读回对账 + 审计”的真实问题；再用 Spring Boot 把同类问题做成**多租户、可水平扩展**的平台，并用压测数据说明设计取舍。

## 2. 技术栈与取舍

| 选择 | 理由（面试可讲） |
| --- | --- |
| Java 21 + Spring Boot（开工时取 Maven Central 最新稳定版） | 虚拟线程承接大量阻塞式 HTTP 投递 |
| MySQL 8.4 LTS | 事务外箱、唯一索引幂等、`FOR UPDATE SKIP LOCKED` 抢任务 |
| Redis 7 | 幂等快速路径、Lua 令牌桶限流 |
| MyBatis（XML 写关键 SQL） | 抢任务、批量更新要手写 SQL，国内面试也更常见 |
| Flyway | 数据库迁移版本化 |
| Testcontainers + JUnit 5 | 集成测试跑真实 MySQL / Redis |
| Micrometer + Prometheus 端点 | 成功率、P99 延迟、积压量 |
| Vue 3 + Vite（控制台） | 全栈部分；复用个人网站的设计令牌 |
| Docker Compose + GitHub Actions | 一键启动、CI |
| k6 | 压测 |

**刻意不用消息中间件**（Kafka / RabbitMQ）：用“MySQL 外箱表 + 轮询抢任务”实现队列。理由：1 核 1G 资源有限；外箱天然解决“业务写入与发消息的一致性”；吞吐够用时少一个组件就少一类故障。面试时说清楚**什么规模下该换 MQ、怎么换**（外箱 + CDC/转发器），比堆组件更加分。

## 3. 核心设计

### 3.1 数据模型

| 表 | 关键字段 |
| --- | --- |
| `tenant` | id、name、api_key_hash、ingest_qps |
| `endpoint` | id、tenant_id、url、secret（加密存储）、event_types、enabled、max_attempts、deliver_qps |
| `event` | id、tenant_id、idempotency_key（`UNIQUE(tenant_id, idempotency_key)`）、type、payload(JSON)、created_at |
| `delivery` | id、event_id、endpoint_id、status、attempts、next_attempt_at、lease_until、last_status_code、last_error；索引 `(status, next_attempt_at)` |
| `delivery_attempt` | delivery_id、attempt_no、started_at、duration_ms、status_code、error、response_snippet（截断） |

状态机：`PENDING → IN_FLIGHT → SUCCEEDED`；失败 `→ PENDING`（退避后重试）；超过次数 `→ DEAD`；人工重放 `DEAD → PENDING`。

### 3.2 主流程

1. **接收** `POST /api/v1/events`（API Key + `Idempotency-Key` 头）：Redis 令牌桶限流（超限 429）→ Redis 幂等快速判重 → **同一事务**写 `event` 并按订阅关系扇出 `delivery`（事务外箱）→ 返回 202。重复请求返回第一次的结果。
2. **抢任务**（多实例安全）：
   `SELECT … FROM delivery WHERE status='PENDING' AND next_attempt_at<=NOW() ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED`，同事务改为 `IN_FLIGHT` 并设租约 `lease_until`，提交后再发 HTTP（**网络请求不放在数据库事务里**）。
3. **投递**：虚拟线程执行，信号量限制总并发与单端点并发；连接/读取超时；请求头带 HMAC 签名。
4. **结果**：2xx 成功；429/5xx/超时按**指数退避 + 抖动**重试；4xx（除 408/429）直接判死信；每次尝试写 `delivery_attempt`。
5. **故障恢复**：清扫任务把租约过期的 `IN_FLIGHT` 退回 `PENDING`（worker 崩溃不丢任务）；优雅停机先停抢任务、等在途投递完成。

### 3.3 可靠性与安全要点（每条都是面试题）

- **至少一次投递 + 接收方幂等**：每次投递带 `X-Relay-Event-Id`，文档说明接收方如何去重；明确“恰好一次”不可得。
- **签名防伪与防重放**：`X-Relay-Signature: t=<时间戳>,v1=<HMAC-SHA256(secret, t.body)>`；接收方校验时间窗（5 分钟）。附 Java / Python 校验示例。
- **SSRF 防护**：登记与投递时都解析域名，拒绝内网、回环、链路本地、元数据地址；禁止重定向到内网。
- **端点熔断**（可选）：连续失败超阈值暂停该端点一段时间，避免拖垮 worker。
- **密钥**：端点 secret 加密存储；API Key 只存哈希。

### 3.4 可观测性

指标：`relay_delivery_total{result}`、`relay_delivery_latency_seconds`（直方图，算 P99）、`relay_backlog`（待投递数）、`relay_oldest_pending_seconds`。日志带 event_id / delivery_id；`/actuator/health` 区分存活与就绪。

### 3.5 演示用“模拟接收方”

内置 `/sink/{name}` 端点，可配置失败率、延迟、返回码。控制台一键“发 1000 个事件到 30% 失败率的接收方”，实时看到重试、死信和最终成功率——这是现场演示的高光时刻。

## 4. 第 1 周开发节奏（每天有可演示产出）

优先级：**必做**（不做就不完整）/ **应做**（明显加分）/ **可选**（有时间再做）。每天结束 `main` 可运行、CI 绿。

| 天 | 目标 | 必做 | 应做 / 可选 | 当天验收 |
| --- | --- | --- | --- | --- |
| **D1** | 骨架与接收 | 项目结构、Compose（MySQL/Redis）、Flyway 建表、租户/端点 CRUD、`POST /events` + 事务外箱、CI | API Key 鉴权 | `curl` 发事件后库里有 event + delivery |
| **D2** | 投递引擎 | `SKIP LOCKED` 抢任务、租约、虚拟线程投递、指数退避、死信、过期租约清扫、优雅停机 | 单端点并发上限 | 两个实例同时跑不重复投递（测试证明） |
| **D3** | 幂等·限流·签名 | Redis 幂等快速路径 + 唯一索引兜底、Lua 令牌桶（接收侧）、HMAC 签名 + 校验示例、SSRF 防护 | 投递侧限流、端点熔断 | 集成测试覆盖：重复请求、超限 429、签名校验、内网地址被拒 |
| **D4** | 测试与指标 | Testcontainers 集成测试补齐、Micrometer 指标、模拟接收方 | Grafana 面板（本地 Compose） | 测试全绿；`/actuator/prometheus` 有指标 |
| **D5** | 压测与调优 | k6 脚本（接收吞吐、投递延迟、30% 失败率场景）、记录数据、调线程/连接池/批大小 | JVM 小内存参数对比（堆大小、GC） | 一张“调优前后”数据表 |
| **D6** | 控制台 | Vue 控制台：事件列表、投递详情（每次尝试）、死信重放、成功率图表、“一键演示” | 租户设置页 | 浏览器里能完整演示一次 |
| **D7** | 部署与文档 | 演示环境上线、README（一键启动、架构图、API）、项目案例文章发到个人网站 | 演示视频 / GIF | 别人照 README 能跑起来 |

**砍需求的顺序**（时间不够时）：先砍可选，再砍“租户设置页、投递侧限流、熔断”，绝不砍 D2 的可靠性核心和 D3 的幂等/签名。

### 部署（D7 定）

服务器 1 核 1G，VPN 优先。两种方案，D7 按 D5 测得的实际内存决定：

- **A. 放 VPS**：MySQL（`innodb_buffer_pool_size=64M`、关 performance_schema）+ Redis（`maxmemory 32mb`）+ JVM（`-Xmx160m`、Serial/G1 对比结果），每个服务 systemd/Compose 设硬内存上限，总预算 ≤ 350 MB；超限时演示服务先被杀，VPN 不受影响。地址如 `relay.huantongxue.party`。
- **B. 不上线**：Docker Compose 一键启动 + 演示视频，个人网站放案例文章和视频。

## 5. 第 2 周：项目理解与八股（建议安排）

| 天 | 内容 |
| --- | --- |
| D8 | 自己从零讲一遍架构（不看代码画图）；整理“项目 → 八股”对照表（下节） |
| D9 | MySQL：事务/隔离级别/MVCC、锁（行锁、间隙锁、`SKIP LOCKED`）、索引与执行计划（拿项目里的抢任务 SQL 做 EXPLAIN） |
| D10 | Redis：数据结构、Lua 原子性、缓存三大问题、分布式锁；限流算法对比（固定窗口/滑动窗口/漏桶/令牌桶） |
| D11 | Java 并发：线程池参数、虚拟线程原理与适用场景、`CompletableFuture`、JVM 内存与 GC（结合 D5 数据） |
| D12 | 分布式：消息可靠性、幂等、至少一次/恰好一次、外箱模式、什么时候引入 MQ；Spring 核心（IoC/AOP/事务传播） |
| D13 | 运维向：讲门户项目的发布、备份、恢复演练、监控；Linux/网络常见题 |
| D14 | 模拟面试：自我介绍 + 两个项目各 5 分钟讲述 + 追问清单逐条过 |

## 6. 项目 → 八股对照表（开发时同步维护）

| 面试问题 | 在项目里的答案位置 |
| --- | --- |
| 怎么保证消息不丢？ | 事务外箱 + 租约 + 过期清扫（`DeliveryClaimer`、`LeaseSweeper`） |
| 怎么保证不重复？ | 接收侧幂等键（Redis + 唯一索引）；投递是至少一次，接收方按 event_id 去重 |
| 多实例怎么不抢重？ | `FOR UPDATE SKIP LOCKED` + 状态切换在同一事务 |
| 限流怎么做？为什么用令牌桶？ | Redis Lua 令牌桶，允许突发、原子执行 |
| 为什么网络请求不放事务里？ | 长事务持锁、连接池耗尽；先提交租约再投递 |
| 虚拟线程适合什么？ | 大量阻塞 IO；注意 pinning、限制并发用信号量而不是线程池大小 |
| 为什么没用 MQ？ | 规模与资源取舍；升级路径：外箱 + 转发到 Kafka |
| 怎么防 SSRF？ | 解析后校验 IP 段、禁内网重定向 |
| P99 是多少？瓶颈在哪？ | D5 压测数据表 |

## 7. 仓库与公开

- 新仓库 `webhook-relay`（公开），与门户仓库分开；不放任何真实密钥，演示配置用 `.env.example`。
- 门户项目脱敏公开属于可选项：第 2 周若有余力，只公开架构与运维文档（发布流程、备份恢复、监控），不公开业务代码与部署记录。
