# 进度

计划见 [PLAN.md](PLAN.md)。每天结束：`./mvnw verify` 全绿，本表更新验收结果。

## 已定决策

- **2026-10-14**：代码为独立公开仓库（本目录），不放入私有的 `huantongxue` 仓库；个人网站 `site/` 只加项目卡片与案例文章（D7）。
- **2026-10-14**：演示子域名建议用 `webhook.huantongxue.party`，避免与门户里已有的 AI 中转（`api.` / `portal/relay/`）混淆；D7 部署时确认。
- 版本（2026-10-14 取自 Maven Central）：Spring Boot 4.1.1（Jackson 3、Flyway 12.4、MySQL Connector 9.7、Testcontainers 2.0.5），MyBatis Starter 4.1.0。
- 本机已有服务占用 3306，Compose 的 MySQL 映射到 **3307**（D5 起改为 **13306**：Windows 重启后 3307 落入系统保留端口段）。
- **2026-10-15：部署采用方案 B（不上线）**。依据 D5 实测内存（全套刚启动约 400 MiB、负载后约 550 MiB，超出 350 MB 预算；VPS 可用约 526 MB）。D7 交付：Compose 一键启动、演示视频/GIF、个人网站案例文章与项目卡片。
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

- 线上：已推送（ed32d1c）。

## D3 幂等·限流·签名 — 完成（2026-10-14）

- 必做全部完成：
  - Redis 幂等快速路径（`IdempotencyCache`，提交后写入、24 h TTL）+ 唯一索引兜底；
  - Lua 令牌桶（`scripts/token_bucket.lua` + `TokenBucket`，用 Redis `TIME`），接收侧按租户限流，429 + `Retry-After`；重放请求也计数；
  - HMAC-SHA256 签名（`WebhookSignature`，每次尝试重新签名）+ Java/Python 校验示例（`examples/verify_signature.py`），两边用同一测试向量互校；
  - SSRF 防护（`SsrfGuard`）：登记与投递前各查一次，按解析后 IP 判断，覆盖十进制/短写 IPv4、IPv4 映射/兼容/NAT64/6to4 IPv6；`relay.ssrf.allowed-hosts` 按字面主机名放行（生产为空，测试只放 127.0.0.1、example.com）。
- 应做全部完成：投递侧按端点 `deliverQps` 限流、端点熔断（`EndpointGuard`，状态在 Redis，多实例共享）。
- 验收：`./mvnw verify` 共 80 项通过（新增 SSRF 30、签名 5、接收侧保护 12、投递侧 3、Redis 故障放行 2）。投递与接收保护两组测试连续重复 3 次均通过。
  - 集成测试覆盖：重复请求（Redis 命中时删库仍返回首次结果，证明不经数据库；缓存未命中回落数据库）、超限 429、租户间限流隔离、签名可被接收方校验、内网地址登记被拒（localhost、127.1、2130706433、10.x、192.168.x、169.254.169.254、[::1]、0.0.0.0）、投递前复查拦截已改指内网的端点、投递侧限流不浪费尝试次数、熔断后停止请求。
- 真实运行：生产默认配置下登记 `localhost`、`169.254.169.254`、`10.0.0.1` 均返回 `url_not_allowed`；`ingestQps=5` 连发 20 个，13 个 202、7 个 429；Python 接收方用 `examples/verify_signature.py` 校验 14 次投递，全部签名有效。
- 修正：
  - 熔断/限流判断原先在拿端点并发许可之前，排队中的任务看不到刚打开的熔断；改为拿到许可后再判断（熔断测试据此只放过 3 次请求）。
  - Compose 中 Redis 由 `noeviction` 改为 `allkeys-lru` 且不持久化：Redis 只存可丢、带 TTL 的数据，满了应淘汰而不是拒写。
- 已知局限（面试可讲）：SSRF 检查与 HttpClient 建连之间各自解析 DNS，仍有 rebinding 时间窗；熔断冷却后是“放行全部 + 一次失败即重开”，不是严格的单请求半开；被限流的任务会退回数据库再抢，限流很紧时有额外的数据库写。

## D4 测试与指标 — 完成（2026-10-14）

- 必做全部完成：
  - Micrometer 指标（`RelayMetrics`）：`relay_delivery_total{result}`、`relay_delivery_latency_seconds`（端到端直方图）、`relay_delivery_attempt_seconds{outcome}`、`relay_backlog`、`relay_oldest_pending_seconds`、`relay_deliveries{status}`、`relay_delivery_deferred_total{reason}`、`relay_ingest_total{result}`；积压类由 `BacklogMetricsRefresher` 每 5 秒刷新。就绪探针包含数据库。日志带 delivery/event ID。
  - 模拟接收方 `/sink/{id}`：按租户创建、配置失败率/状态码/延迟，统计重复投递；SSRF 只放行 `<base-url>/sink/<id>` 这一种形状（路径穿越、编码穿越、同主机其他路径均拒绝）。
  - 集成测试补齐：死信重放（预算重置、`attempt_no` 连续）、停用端点拒绝重放、指标计数与积压、模拟接收方。
- 提前完成：死信人工重放（V2 迁移 `attempts_base`/`replay_count`；单条与按端点批量；投递列表与详情 API）。
- 应做完成：Grafana 面板（Compose `monitoring` profile，Prometheus v3.5.0 + Grafana 12.1.0，14 个面板，预置数据源与面板）。
- 验收：`./mvnw verify` 共 86 项通过。真实运行：V2 迁移在已有数据的库上成功；模拟接收方 30% 失败 + 20–50 ms 延迟，200 个事件 → 接收方 279 次请求、199 个成功、0 重复，`/actuator/prometheus` 指标名与计划一致；Prometheus 目标 up、Grafana 经数据源查询到数据、浏览器中 14 个面板均有数据。
- D5 线索（真实数据）：
  - 端点默认 `deliverQps=20` 时 200 个事件产生 552 次“限流退回”（每次一来一回两次数据库写）——限流过紧时的放大效应，D5 调优对象。
  - 堆上限为本机默认 8 GiB，上 1 GB 服务器前需定 `-Xmx`；连接池峰值 9/10 活跃，接近打满。
- 环境：本机 9090 被代理客户端占用，Prometheus 主机端口改为 `PROMETHEUS_PORT`（默认 9091）。
- 未验证：带 delivery/event ID 的日志行（本次运行无投递路径内的日志输出，只确认了空值时不残留标记）。

- 线上：已推送（5d60759）。

## D5 压测与调优 — 完成（2026-10-15）

详细数据与过程见 [BENCHMARK.md](BENCHMARK.md)。

- 必做全部完成：k6 脚本（`bench/k6/`：接收吞吐、投递、30% 失败率、投递侧限流）、结果整理（`bench/report.py`：Prometheus 快照差值算分位数、Hikari 等待/占用、GC、容器 CPU/内存）、调线程/连接池/批大小并记录前后数据。
- 应做完成：JVM 小内存参数对比（`-Xmx160m`/`128m`、Serial/G1）。
- 环境：应用打包为镜像（`Dockerfile`，Compose `app` profile），`cpus=1` 模拟 1 核 VPS；k6 用官方镜像在同一网络里跑。
- 调优前后（同环境）：接收容量约 400 → 600 次/秒（P99 48 ms）；过载 1000 次/秒由雪崩（P50 1.5 s）变为限载（约 700 次/秒照常、P99 149 ms、其余 503）；投递侧限流退回 14 970 → 0；低负载端到端 P50 0.37 → 0.11 s；应用内存峰值 379 → 约 300–327 MiB；MySQL 342 → 约 230 MiB。
- 代码改动：连接池默认 20；`TenantLookup`（鉴权查租户 Caffeine 缓存，轮换 Key 时失效）；`IngestAdmission`（接收并发上限 64，过载 503 + Retry-After）；令牌桶支持**预约**，投递侧限流在预算内直接占令牌原地等（修复惊群）；`DeliveriesCreated` 事件唤醒投递循环；`ThreadLocalRandom` 替换共享的 `RandomGenerator.getDefault()`（精简 JRE 缺模块导致启动失败，且原实现非线程安全）；`ApiException` 统一携带 Retry-After。
- 配置改动：Compose 默认 `-Xmx128m` Serial + 代码缓存/元空间上限、容器 352m；MySQL 收紧临时表/连接/表缓存；MySQL 主机端口改为 **13306**（Windows 重启后 3307–3406 被系统保留）。
- 验收：`./mvnw verify` 共 90 项通过（新增：轮换 Key 立即失效、接收后发布唤醒事件而重放不发布、过载 503、唤醒不等轮询、限流退回次数上限）。
- D7 决策输入：全套（应用 + MySQL + Redis）刚启动约 400 MiB、负载后约 550 MiB，超出计划的 350 MB 预算；VPS 可用约 526 MB。建议方案 B 或先评估 A'（见 BENCHMARK.md“部署内存预算”）。
- 未验证：数值来自本机 Docker Desktop，未在真实 VPS 上测；单次测量为主，存在本机波动。

- 线上：已推送（a08584d）。

## D6 控制台 — 完成（2026-10-15）

- 必做全部完成（`console/`，Vue 3.5 + Vite 8 + vue-router 4 + TypeScript，版本均发布满两周并锁定）：
  - 登录（租户 API Key，存 sessionStorage）；概览（事件数、按状态的投递数、近 1 小时尝试成功率、60 分钟每分钟成功/失败堆叠柱状图，纯 SVG）；事件列表（键集分页）与详情（payload、各端点投递）；投递列表（按状态筛选）与详情（每次尝试：时间、耗时、状态码、响应片段/错误）；死信单条重放与按端点批量重放；端点列表、启用/停用、新建（secret 只显示一次）；一键演示（实时进度、失败数、重复数，可中途把接收方失败率改为 0）。
- 后端新增：`GET /api/v1/stats`（V3 迁移加 `delivery_attempt(started_at)` 索引）、`POST /api/v1/demo`（服务端写入事件，每次演示独立事件类型，旧演示的模拟接收方删除、端点停用）、控制台静态托管（`/console/`，`/` 重定向；`relay.console.dir`）与严格 CSP（只允许同源脚本，降低 API Key 被 XSS 读走的风险）。
- 构建与交付：控制台不嵌入 Maven 构建；CI 新增 `console` 任务（`vue-tsc` 类型检查 + 构建）；镜像把 `console/dist` 拷入 `/app/console`。主包 36.8 KB（gzip），各页面 1–2.4 KB。
- 验收：`./mvnw verify` 93 项通过（新增概览统计、一键演示、静态托管与安全头 3 项）。真实运行（本机后端 + 浏览器）：登录 → 一键演示 300 个事件、30% 失败 → 300/300 送达、122 次失败均重试成功、0 重复、0 死信；“修好了”按钮生效；用始终返回 400 的模拟接收方造出 3 条死信 → 单条重放后详情页显示尝试 1 为 400、尝试 2 为 200、人工重放 1 次 → 端点页批量重放其余 2 条；浏览器控制台无报错、无 CSP 违规。
- 未做/未验证：应做的“租户设置页”未做（端点管理已覆盖主要设置；租户配额与 Key 轮换属管理员接口）。浏览器面板处于隐藏状态，只做了功能与文本核对，**未做视觉截图与手机宽度检查**。

- 线上：已推送（20f2b17）。**补记**：D5 推送后 CI 实际失败（`deliveryRateLimitSpacesOutRequestsWithoutBurningAttempts` 的退回次数断言在 CI 上为 12 > 10），当时未检查 CI，D6 推送时才发现；D7 修复（见下）。

## D7 部署与文档 — 完成（2026-10-16，方案 B）

- **一键启动**：`Dockerfile` 改为多阶段构建（JDK 编译 jar、Node 构建控制台、JRE 运行），只需 Docker；`scripts/quickstart.sh` 生成随机口令的 `.env` → 构建并启动 → 创建演示租户 → 打印控制台地址与 API Key。在“全新副本”（仅含仓库文件，无 `.env`/`node_modules`/`target`，独立 Compose 项目名）中实测通过；随后用打印的 Key 跑演示 200 个事件、30% 失败：60 秒时 198/200 送达（其余 2 条在退避中）、0 重复、0 死信。
- **README** 重写：一键启动、架构图与投递状态机（Mermaid）、设计要点（可靠性 / 幂等限流过载 / 安全 / 性能）、API、签名校验、可观测性、取舍。
- **面试对照表** `docs/INTERVIEW.md`：按可靠性、MySQL、Redis、并发与 JVM、Spring、安全、测试运维分组，每条“答案 + 代码位置”。
- **个人网站**（`huantongxue` 仓库，未提交）：`site/src/data/projects.ts` 新增项目卡片（链接到案例文章），`site/src/content/posts/webhook-relay.md` 案例文章；本地 `npm run build` 通过，零 JS、CSS 10.4 KB、首页 20 KB，满足该仓库 CI 预算。
- **修复 D5 的不稳定测试**：根因是投递侧被拒的任务同一时刻拿到同一个等待时间，一起回来再撞（惊群下移一层）；退回延迟改为 [delay, 2×delay] 随机，断言改为“平均每条 ≤ 2 次”。本地连续 3 次通过。
- **端口**：Windows 重启后又保留了 6285–6384，Redis 主机端口改为 **16379**（MySQL 13306 同理）。
- 验收：`./mvnw verify` 93 项通过；一键启动在全新副本中通过。
- **截图与 GIF**：在一键启动的环境里跑 1000 个事件、30% 失败的演示录制 `docs/images/demo.gif`（8 帧，71 KB），另有概览与投递详情截图（WebP，19–25 KB），顶栏的 Key 前缀已涂掉。README 顶部与网站文章引用；网站侧经 Astro 图片管线压缩、懒加载，构建后零 JS、CSS 10.4 KB、首页 20 KB。
- 录制中出现 1 条死信：连续 8 次 503（30% 失败率下每条约 0.0066%，1200 条里出现至少一条的概率约 7.6%），重试间隔从 5 秒增长到约 5 分钟，符合设计；用重放接口重放后第 10 次尝试成功。
- 视觉检查：概览、演示、投递详情、文章页在浏览器里看过，布局与配色正常；未检查手机宽度。
