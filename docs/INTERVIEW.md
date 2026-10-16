# 面试问题 → 项目里的答案

每条：一句话答案 → 代码位置 → 可能的追问。数据出处见 [BENCHMARK.md](BENCHMARK.md)。

## 可靠性与分布式

| 问题 | 项目里的答案 | 代码 |
| --- | --- | --- |
| 怎么保证消息不丢？ | 事务外箱：事件与投递任务同一事务写入，202 之后任务已持久化；投递前设租约，实例崩溃后租约过期被清扫退回 PENDING。 | `event/EventService.java`（`insertWithFanOut`）、`delivery/DeliveryStore.java`（`claim`）、`DeliveryMapper.xml`（`sweepExpiredLeases`） |
| 怎么保证不重复？ | 接收侧：`UNIQUE(tenant_id, idempotency_key)` + 请求摘要（同键不同内容 422），Redis 只做提交后的快速路径。投递侧是至少一次，接收方按 `X-Relay-Event-Id` 去重。 | `V1__init.sql`、`EventService.replay`、`event/IdempotencyCache.java` |
| 并发的两个同键请求会怎样？ | 第二个 INSERT 在唯一索引上等第一个事务提交，随后抛 DuplicateKeyException；在**新的语句**里读出已提交的行返回（READ COMMITTED）。测试：8 线程同键只产生 1 个事件。 | `IngestIntegrationTest.concurrentSameKeyCreatesExactlyOneEvent` |
| 多实例怎么不抢重？ | `FOR UPDATE SKIP LOCKED` 锁住到期行、同事务改 IN_FLIGHT；实例间不需要通信。测试：两个 worker 300 条，每条恰好送达一次。 | `DeliveryMapper.xml`（`lockClaimable`、`markInFlight`）、`DeliveryEngineIntegrationTest.twoWorkersDeliverEveryEventExactlyOnce` |
| 租约过期后旧实例又写回结果怎么办？ | fencing：回写带 `attempts = 抢到时的值` 条件，被重新抢走后旧结果更新 0 行；尝试记录保留作审计。 | `markSucceeded/markRetry/markDead`、`expiredLeaseIsSweptAndStaleResultIsFencedOff` |
| 为什么网络请求不放在事务里？ | 慢端点会让行锁和数据库连接一直被占用，连接池耗尽后整个服务停摆；先提交租约再投递。 | `DeliveryStore.claim` 注释 |
| 至少一次 / 恰好一次？ | 网络两端无法区分“对方没收到”和“对方处理了但响应丢了”，恰好一次不可得；至少一次 + 接收方幂等是工程上的标准解。 | README“设计要点” |
| 为什么不用 MQ？什么时候该换？ | 1 核 1G、吞吐够用时少一个组件少一类故障；外箱天然解决“写库与发消息”的一致性。实测单核接收约 600 次/秒；更高吞吐或多消费方时，把外箱表经 CDC（Debezium）/转发器写入 Kafka，投递消费 Kafka。 | `docs/PLAN.md` 第 2 节 |
| 重试策略？ | 2xx 成功；408/429/5xx/网络错误重试，其余 4xx/3xx 直接死信；指数退避 + equal jitter，尊重 `Retry-After`；死信可人工重放（重置本轮次数、保留历史）。 | `delivery/RetryPolicy.java`、`V2__delivery_replay.sql` |
| 优雅停机？ | `SmartLifecycle.stop`：先停抢任务，再等在途投递写回（信号量全部归还），超时中断，剩下的靠租约过期兜底。 | `DeliveryWorker.stop`、`gracefulStopWaitsForInFlightDeliveries` |

## MySQL

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 抢任务 SQL 走什么索引？ | `idx_delivery_claim(status, next_attempt_at)`，EXPLAIN：`type=range`、`Using index`（覆盖），7 万行时 `rows=1`。 | `V1__init.sql`、BENCHMARK.md 第 8 节 |
| 为什么用 READ COMMITTED？ | SKIP LOCKED 查询不加间隙锁，减少锁冲突；唯一键冲突后能读到对方已提交的行。 | `application.yml`（Hikari `transaction-isolation`）、`compose.yaml` |
| 深分页怎么做？ | 键集分页（`id < ? ORDER BY id DESC LIMIT ?`），走 `(tenant_id, id)` 索引，不用 OFFSET。 | `EventMapper.xml`（`findPage`） |
| 连接池多大合适？怎么判断？ | 看 Hikari“获取连接等待”与“占用时间”：基线等待 662 ms、占用 12 ms 即池子是瓶颈；10 → 20 后容量 400 → 约 650 次/秒，瓶颈转为 CPU，再加无益。 | BENCHMARK.md 第 1、3 节 |
| 小内存 MySQL 怎么调？ | 64M 缓冲池、关 performance_schema、限制连接数、`temptable_max_ram`（默认 1G）等：342 → 约 230 MiB。 | `compose.yaml` |
| 时区坑？ | `@ServiceConnection` 会替换整个 JDBC URL，写在 URL 里的 `connectionTimeZone=UTC` 被丢掉；改放 Hikari `data-source-properties`，测试锁住。 | `application.yml`、D2 记录 |

## Redis

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 为什么用令牌桶？对比其他算法？ | 固定窗口有边界两倍流量；滑动窗口精确但存储大；漏桶平滑但不允许突发；令牌桶允许突发、长期限速。 | `scripts/token_bucket.lua` |
| Lua 为什么原子？时间取哪里？ | Redis 单线程执行脚本，读-算-写不被打断；时间取 Redis `TIME`，多实例不受各自时钟偏差影响。 | 同上 |
| 惊群问题怎么解决？ | 令牌预约：等待在预算内直接扣令牌（可为负）并返回精确等待时间，后来者自然排队；预算外的退回带抖动。实测退回 14 970 → 0。 | `TokenBucket.reserve`、`DeliveryWorker.deliver`、BENCHMARK.md 第 5 节 |
| Redis 挂了怎么办？ | 只放可丢数据：限流、幂等缓存、熔断都 fail-open；就绪探针不含 Redis；淘汰策略 `allkeys-lru`、不持久化。 | `TokenBucket`、`IdempotencyCache`、`RedisFailOpenTest`、`compose.yaml` |
| 缓存一致性？ | 幂等缓存只在事务提交后写入，未命中/过期回落数据库，结果一致；正确性只依赖唯一索引。租户缓存 30 秒 TTL，轮换 Key 时本实例主动失效。 | `IdempotencyCache`、`tenant/TenantLookup.java` |

## Java 并发与 JVM

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 虚拟线程适合什么？ | 大量阻塞 IO（HTTP 投递）；等待网络时不占平台线程。限制并发用信号量（全局 + 单端点）而不是线程池大小——稀缺的是下游和连接，不是线程。 | `DeliveryWorker`、`DeliverySender` |
| 虚拟线程的坑？ | pinning（`synchronized` 内阻塞）；共享的非线程安全对象：项目里曾把 `RandomGenerator.getDefault()` 共享给几百个投递线程（且精简 JRE 缺该实现导致启动失败），改为 `ThreadLocalRandom`。 | BENCHMARK.md“踩到的坑” |
| 1 核机器选什么 GC？ | Serial：G1 的并发线程与业务抢唯一的核，实测延迟和 CPU 都更差；`-Xmx128m` + 限制代码缓存/元空间比 160m 省约 60 MiB。 | BENCHMARK.md 第 7 节 |
| 过载为什么会雪崩？怎么防？ | 排队请求撑满堆 → GC 停顿发生在持有数据库连接时 → 占用变长 → 更多排队。接收并发上限 + 立即 503。 | `event/IngestAdmission.java`、BENCHMARK.md 第 2、4 节 |

## Spring

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 为什么用 TransactionTemplate 而不是 @Transactional？ | 事务边界一目了然；避免同类自调用不走代理导致事务不生效；需要在事务外捕获唯一键冲突再读。 | `EventService.ingest` |
| 鉴权为什么没用 Spring Security？ | 两类 Bearer（管理口令、租户 Key）用两个拦截器足够，抛出的异常统一走 ProblemDetail；需要 OAuth/会话时再引入。 | `security/AuthInterceptors.java` |
| 生命周期怎么挂？ | `SmartLifecycle`：上下文就绪后开始抢任务，停机时先于连接池关闭。 | `delivery/DeliveryConfig.java` |
| 事件驱动解耦？ | 接收提交后发布 `DeliveriesCreated`，投递循环监听并立即醒来；低负载端到端延迟 0.37 → 0.11 秒。 | `EventService`、`DeliveryConfig.onDeliveriesCreated` |

## 安全

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 怎么防 SSRF？ | 解析后按 IP 判断（含十进制 IP、IPv4 映射 IPv6 等写法），登记与投递前各查一次，不跟随重定向；模拟接收方只放行“前缀 + ID”一种形状。局限：DNS rebinding 时间窗。 | `ssrf/SsrfGuard.java`、`SsrfGuardTest` |
| 签名怎么设计？防重放？ | HMAC-SHA256 签“时间戳.原始请求体”，接收方校验 5 分钟窗口、常量时间比较；多个 v1 便于轮换。 | `signature/WebhookSignature.java`、`examples/verify_signature.py` |
| 密钥怎么存？ | API Key 高熵只存 SHA-256（慢哈希只对低熵口令有意义）；端点 secret 要明文签名，AES-256-GCM 加密，主密钥来自环境变量。 | `security/Tokens.java`、`SecretCipher.java` |
| 控制台的 Key 放哪？ | sessionStorage（关标签页即失效）；配合严格 CSP 降低 XSS 读走的风险。 | `console/src/api.ts`、`console/ConsoleConfig.java` |

## 测试与运维

| 问题 | 答案 | 代码 |
| --- | --- | --- |
| 怎么测并发/多实例？ | Testcontainers 起真实 MySQL/Redis；同一 JVM 里起两个 worker、本机 HTTP 接收方统计到达次数。 | `DeliveryEngineIntegrationTest`、`TestReceiver` |
| 压测怎么做、怎么定位瓶颈？ | 容器限 1 核模拟 VPS，k6 固定到达速率；Prometheus 快照差值算分位数，看 Hikari 等待/占用、GC、容器 CPU/内存。 | `bench/`、BENCHMARK.md |
| 不稳定测试怎么处理？ | D5 的“退回次数”断言在 CI 上偶发失败：先找根因（被拒任务拿到同一等待时间，惊群下移一层）→ 加抖动修复 → 断言改为有依据的上界。 | `DeliveryEngineIntegrationTest.deliveryRateLimitSpacesOutRequestsWithoutBurningAttempts` |
| 监控看什么？ | 送达率、端到端 P99、积压与最老等待、死信数、退回原因、JVM 堆、连接池；标签不按租户/端点（基数爆炸）。 | `metrics/RelayMetrics.java`、`ops/monitoring/` |
