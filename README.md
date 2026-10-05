# Tenant API Guard（多租户接口保护与一致性）

在 Spring Boot Web 服务之上提供三层、按租户维度的确定性保护：

1. **配额（Quota）**：按时间窗口限制每租户请求量，并发下不超额、计数不错乱，窗口切换不重计不漏计；
   支持两种**按租户可选**的口径——固定时间窗口（默认，历史行为）与滑动窗口（任意连续周期内不超配额）；
   提供只读用量查询，并自动回收不活跃租户的运行时状态；
2. **幂等（Idempotency）**：写操作按幂等键去重，并发重复提交只产生一次实际效果并返回一致结果，同键不同内容明确拒绝，TTL 过期语义确定；
3. **舱壁（Bulkhead）**：每租户独立并发槽 + 全局资源信号量，单租户突发/异常不影响其他租户，资源紧张时可控降级。

所有拒绝都通过**稳定原因码**表达，附带判定依据明细，绝不静默丢弃或返回不确定结果。

---

## 1. 判定流程与稳定原因码

请求经 `GuardInterceptor`（匹配 `/api/**`）按固定顺序处理：

```
X-Tenant-Id 识别租户
        │ 缺失 → 400 MISSING_TENANT
        ▼
配额 tryAcquire（按租户口径 FIXED/SLIDING 判定是否占用一个额度）
        │ 租户配额<=0/未配置 → 403 TENANT_NOT_CONFIGURED
        │ 窗口已用尽        → 429 QUOTA_EXCEEDED（detail: windowIndex/窗口起止/consumed/limit）
        ▼
租户舱壁 tryAcquire（非阻塞）
        │ 该租户并发槽已满   → 429 BULKHEAD_FULL（detail: inUse/maxPermits）
        │ 全局信号量耗尽    → 503 RESOURCE_PRESSURE（本地资源紧张的可控降级）
        ▼
Controller → 写服务内部做幂等判定
        │ 缺 X-Idempotency-Key                 → 400 MISSING_IDEMPOTENCY_KEY
        │ 同键不同内容（即使首次仍在执行）      → 409 IDEMPOTENT_CONTENT_CONFLICT
        │ 首次执行仍在进行且等待预算耗尽        → 409 IDEMPOTENT_IN_PROGRESS
        ▼
正常响应（写响应含 replay 标记与 idempotencyExpireAtMs）
```

原因码集中定义在 `model/RejectReason.java`，每个码绑定固定 HTTP 状态，可通过
`error.reason` 稳定区分；`error.detail` 给出“为什么是这个结论”的依据。

---

## 2. 配额模型（两种口径，按租户可选）

配额口径由配置项 `quota-mode` 选择，**默认 `FIXED`，不配置的老租户行为与以前完全一致**；
两种口径可同时存在，各租户各选各的。

### 2.1 FIXED：固定时间窗口（默认，历史口径）

- 窗口由 `floor(now / windowMs)` 划分，窗口序号、起止时间会出现在日志与拒绝明细中；
- **每个网格窗口各有独立计数**：运行时状态按窗口序号分别记账，一个窗口的计数**永远不会
  因为另一个窗口的请求到来而被清零**。只保留最新相邻的两个窗口槽位（当前窗口与前一窗口，
  正好覆盖交界批次），更早窗口的迟到请求保守拒绝，内存占用为常量；
- **交界并发确定性（已修复）**：一批请求一半压在交界前一刻（如 999）、一半压在后一刻
  （如 1000）时，无论线程谁先拿到执行权、时间戳以什么顺序交错判定，结论都只取决于各请求
  自身落在哪一格——前后两个窗口各算各的，每段放行数都不超过该租户配额 L，整批放行总数
  恒为确定值 2L（若某一段本身请求数不足 L 则相应更少），不会出现随调度飘忽的超额放量；
- **每个租户独立状态与锁**：计数自增、新窗口开账都在同一把锁内原子完成。单窗口内并发 N
  个请求、配额 L 时，结果严格为“恰好 L 个放行 + N−L 个 `QUOTA_EXCEEDED`”；
- **运行期配额调整即时按新上限判定**：判定每次都读取该租户最新配置，窗口内调大立即放开
  剩余额度、调小立即收紧，不存在旧窗口状态残留导致的多放/少放；口径本身不变时不重置计数；
- 与滑动口径的差异：请求卡在窗口交界时，前一窗口最后几笔与后一窗口开头几笔可能在很短
  时间内合计接近两倍配额（两窗口各自合法），这是固定窗口口径的固有语义；需要“任意连续
  周期都不超配额”的更平滑限流请用 `SLIDING`。

### 2.2 SLIDING：滑动窗口（滑动日志口径）

- 判定区间为任意长度 `windowMs` 的连续时间：以当前时刻 `now` 为右端，存活放行记录为
  区间 `(now-windowMs, now]`（左开右闭；时间戳在 `now-windowMs` 时恰好滑出失效）；
- 区间内存活放行数 `< quota` 才放行，并把本次时刻记入日志；否则 `QUOTA_EXCEEDED`；
- 因此**任意一段长度为配额周期的连续时间里，放过去的量都不超过配额**，窗口交界处不会
  出现两段叠加放量；
- 每租户用一个按时间升序的时间戳队列记录放行时刻，过期时间戳在下一次真实占用时惰性摘除；
- 滑动口径没有“窗口交界”，配额调整即时生效（不回溯历史，但立即按新上限判定）。

### 2.3 并发确定性、配置隔离与口径切换

- **每个租户独立状态、独立锁**：计数自增/新窗口开账/时间戳入队都在租户自己的锁内原子完成。
  一批请求即使一半压在交界前一刻、一半压在后一刻，放行总数也严格确定，与线程调度先后无关：
  FIXED 下为“前后两格各 ≤ L、合计确定”，SLIDING 下为“同一连续周期内 ≤ L”；
- 租户之间状态完全隔离：不同口径、不同配额互不串味；
- 运行期修改某租户 `quota`：FIXED 与 SLIDING 均立即按新上限判定（不回溯已放行记录，但
  同一窗口/区间的后续请求立即跟新配置走，无旧状态残留）；
- 运行期修改某租户 `quota-mode`：该租户旧口径运行时状态被**原子封存丢弃**，从新口径的
  空状态重新计数；其他租户继续各自的口径，不受影响。

### 2.4 只读用量查询

- 接口：`GET /internal/quota?tenant=<id>`（路径不在 `/api/**` 守卫范围内，**查询本身不占
  配额、不修改任何运行时状态**，连滑动队列的过期时间戳都不会因查询被摘除）；
- 返回：`mode`（口径）、`used`（已用）、`remaining`（剩余）、`limit`、`interval`
  （所算区间的起止与窗口序号）、`wouldAllow`（此刻再来一个是否放行）、`reason`、
  `observedAtMs`；
  - FIXED 的 interval 是当前固定格 `[floorStart, floorStart+windowMs)`；
  - SLIDING 的 interval 是 `[now-windowMs, now)`（左闭右开的展示口径，存活判定见 2.2）；
- **结论一致性**：查询与真实放行共用同一段锁内判定代码、基于同一份运行时状态，因此同一
  时刻“查询说还有余量”就一定能放，“查询说满了”真实请求就一定被打回，不会自相矛盾。

### 2.5 不活跃状态回收

- 长期挂着的服务里，很久不来请求的租户其运行时状态会被后台任务
  `QuotaStateEvictor` 周期回收（间隔 `quota-evict-interval-ms`，空闲阈值
  `quota-idle-evict-ms`；阈值 `<=0` 关闭回收）；
- 回收只移除“**移除后不会让后续判定变宽松**”的状态：
  - FIXED：计数所属固定窗口已整体过去，或当前窗口计数为 0；
  - SLIDING：时间戳队列里没有任何仍存活的放行记录；
- 扫描对租户锁只做非阻塞 `tryLock`，租户正好在判定时立即跳过，**不拖慢、不影响活跃租户**；
  状态被回收后，该租户下次请求以空状态自然重建。
- 本地内存模型：状态按租户隔离，单租户异常不会破坏其他租户的计数。

---

## 3. 幂等语义

幂等键为 `(tenant, X-Idempotency-Key)`，请求内容以规范化字符串的 SHA-256 作为指纹。

| 情形 | 结论 | HTTP / 行为 |
|---|---|---|
| 键不存在（或已过期） | `FIRST` | 唯一执行者（putIfAbsent + single-flight），业务动作**仅发生一次**，结果随首次响应返回 |
| 并发相同内容，首次未完成 | 等待 | 在该键条件变量上等待；首次完成后拿到**与首次完全一致**的结果（`REPLAY`） |
| 等待超过 `in-progress-timeout-ms` | `IN_PROGRESS` | 409；不猜测成败、不重复执行；稍后重试可得一致结果 |
| 相同键、不同内容指纹 | `CONFLICT` | 409 `IDEMPOTENT_CONTENT_CONFLICT`，无论首次是否完成；响应含 `submittedHash/firstHash`；不污染原记录 |
| 记录到达 TTL | 等同不存在 | `expireAt <= now` 起，同键再次提交是全新的首次请求，**可再产生一次效果**；边界前一刻仍为重放 |
| 首次业务抛异常 | 记录移除 | 异常向上抛出（不伪造成功），等待者被唤醒，同键可重新提交 |

- 存活期从首次登记时刻起算 `idempotency-ttl-ms`；过期记录惰性删除，也可 `purgeExpired()` 主动清理；
- 时间统一来自 `TimeSource`（生产为系统时钟，测试为可控时钟），避免墙钟/虚拟时间混用；
- 该实现为**单实例本地内存**实现；多实例部署需替换为共享存储实现（接口语义保持不变）。

---

## 4. 舱壁与降级

- 每租户有独立的并发槽计数，`tryAcquire` **完全非阻塞、不排队**：满了立即 `BULKHEAD_FULL`；
- 获取顺序“先租户槽、后全局信号量”：全局耗尽时回滚租户槽并返回 `RESOURCE_PRESSURE(503)`，
  这是明确的降级结论而非静默丢弃；
- 槽位只在请求真正占有时由 `afterCompletion` 成对释放；
- 并发上限配置对新请求立即生效，不强制中断在途请求。

---

## 5. 配置项（`application.properties`，前缀 `tenant.guard`）

| 配置项 | 默认值 | 含义 |
|---|---|---|
| `window-ms` | 1000 | 配额周期长度（毫秒），两种口径共用 |
| `default-quota` | 10 | 未单独配置租户的每周期配额；**<=0 表示默认拒绝** |
| `default-quota-mode` | `FIXED` | 未单独配置租户的配额口径：`FIXED`（固定窗口，历史行为）/ `SLIDING`（滑动窗口） |
| `idempotency-ttl-ms` | 300000 | 幂等记录存活时长；过期后同键视为首次 |
| `default-max-concurrent` | 20 | 未单独配置租户的并发槽上限；<=0 表示默认拒绝 |
| `global-max-concurrent` | 1024 | 全局并发信号量上限（本地资源降级阈值） |
| `in-progress-timeout-ms` | 10000 | 并发重复请求等待首次完成的预算 |
| `quota-idle-evict-ms` | 1800000 | 配额状态空闲回收阈值（毫秒）；超过该时长无请求且回收不影响判定才回收；**<=0 关闭回收** |
| `quota-evict-interval-ms` | 300000 | 空闲状态后台扫描间隔（毫秒） |
| `tenants.<id>.quota` | — | 指定租户每周期配额；两种口径均在改配置后的下一笔判定立即按新上限生效（不回溯已放行记录） |
| `tenants.<id>.quota-mode` | — | 指定租户配额口径 `FIXED`/`SLIDING`；不配置则回落 `default-quota-mode`（即老行为） |
| `tenants.<id>.max-concurrent` | — | 指定租户并发槽上限 |

示例：

```properties
# 老租户：不配置 quota-mode，沿用默认 FIXED，行为与以前完全一致
tenant.guard.tenants.t1.quota=5
tenant.guard.tenants.t1.max-concurrent=3
# 希望更平滑的租户：任意连续 1000ms 内最多 5 个
tenant.guard.tenants.t3.quota=5
tenant.guard.tenants.t3.quota-mode=SLIDING
tenant.guard.tenants.t3.max-concurrent=10
```

> **兼容范围**：本次仅新增能力，未改变任何默认行为。不设置 `default-quota-mode` 与
> `tenants.<id>.quota-mode` 时，所有租户仍是原来的固定窗口口径；旧的 `peek` 观察方法、
> 拒绝原因码、响应结构保持不变。新查询口子挂在 `/internal/**`，不经过 `/api/**` 守卫。

---

## 6. HTTP 用法

```bash
# 写操作（三重保护 + 幂等）
curl -s -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: t1' -H 'X-Idempotency-Key: order-001' \
  -H 'Content-Type: application/json' \
  -d '{"product":"widget","quantity":2}'

# 只读操作（配额 + 舱壁保护）
curl -s http://localhost:8080/api/hello/bob -H 'X-Tenant-Id: t2'

# 配额用量只读查询（不占额度；FIXED/SLIDING 都能查）
curl -s 'http://localhost:8080/internal/quota?tenant=t3'
# 返回示例：
# {"success":true,"data":{"tenant":"t3","mode":"SLIDING","used":2,"remaining":3,
#  "limit":5,"interval":{"startMs":1727900000000,"endMs":1727900001000,"windowIndex":...},
#  "wouldAllow":true,"reason":null,"observedAtMs":...}}
```

成功写响应：`{"success":true,"data":{"order":"...","replay":false,"idempotencyExpireAtMs":...}}`；
重复提交时 `replay=true` 且 `order` 与首次逐字一致。
拒绝响应：`{"success":false,"error":{"reason":"QUOTA_EXCEEDED","message":...,"detail":{...}}}`。

---

## 7. 本地验证

```bash
# 全量单测（所有用例均为确定性断言）
mvn test

# 只跑“固定窗口交界并发确定性”回归（@RepeatedTest 会连跑 25 轮交界批次）
mvn test -Dtest=FixedWindowBoundaryDeterminismTest

# 想看每笔判定明细时，控制台已默认 INFO 输出；关键字过滤：
mvn test -Dtest=FixedWindowBoundaryDeterminismTest 2>&1 | grep -E "TEST business|quota decision|quota window rollover"
```

交界确定性回归的复现方式（`FixedWindowBoundaryDeterminismTest`）：窗口 1000ms、配额 L，
用远多于 L 的线程（如 80）在同一道栅栏后并发调用，其中一半请求时间戳取 999（窗口
[0,1000)）、另一半取 1000（窗口 [1000,2000)）；同一批输入连续跑 25 轮，断言每轮
放行总数都恒等于 2L、且 999 段与 1000 段各恰好 L。修复前同一程序会输出 99~100 这样
飘忽的放行数，修复后每轮稳定为 10（L=5 时）。

测试清单（全部为确定性并发/边界测试，日志均打印业务、输入与判定结果）：

| 测试类 | 覆盖点 |
|---|---|
| `FixedWindowBoundaryDeterminismTest` | **固定窗口交界并发确定性回归**：80 线程一半压 999 一半压 1000，重复 25 轮放行总数恒为 2L、每段各 L；25 轮结果集合只有一个值；串行打乱同一批时间戳顺序结果一致；FIXED 与 SLIDING 并存同批次各算各的（2L vs L）；运行期配额 3→5→1 立即放开/收紧无残留；不显式配口径的老租户默认仍 FIXED 且交界确定；前后单段各自精确受限 |
| `QuotaServiceConcurrencyTest` | 50 并发/配额 5 恰好放行 5；窗口边界 999→1000 切换、两窗口各计满；租户隔离；未配置租户拒绝（FIXED 老口径，保持全绿） |
| `SlidingQuotaTest` | 滑动口径基本配额；交界叠加场景 FIXED 放两倍 vs SLIDING 不超额；时间戳恰好间隔一周期滑出（左开右闭边界）；两种口径并存各自独立；只读查询不占额度且 `wouldAllow` 与真实放行/拒绝一致 |
| `SlidingQuotaConcurrencyTest` | 20 线程压在 999/1000 交界两侧重复 20 轮，滑动口径放行总数恒等于配额；运行期调配额只影响本租户；运行期 FIXED→SLIDING 切换封存旧状态、其他租户不受影响 |
| `QuotaStateEvictionTest` | FIXED 过期窗口空闲状态回收/活跃窗口有计数不回收；SLIDING 存活时间戳不回收、全部滑出后回收；回收不影响其他活跃租户判定；回收后状态自然重建 |
| `QuotaQueryIntegrationTest` | `/internal/quota` 端到端：查询不占额度、used/remaining/mode/interval 正确、查询与真实请求结论一致、未配置租户 reason 明确 |
| `IdempotencyConcurrencyTest` | 20 并发同键仅 1 次副作用且结果一致；完成后/执行中同键不同内容 CONFLICT；等待超时 IN_PROGRESS；TTL 边界 1999/2000；业务失败可重试 |
| `BulkheadTest` | 舱壁打满确定拒绝与释放回收；单租户突发不影响他人；20 并发/3 槽严格成立 |
| `WebGuardIntegrationTest` | 缺租户头、未配置租户、正常通过、幂等重放/缺键、内容冲突 409、配额打满 429 的端到端原因码 |
| `ApplicationTests` | Spring 上下文装配（含定时回收任务装配） |

运行期判定日志关键字：`quota decision`（含 `mode=`、`consumed=x/y`、`interval=[..,..)`、
`decision=`）、`quota inspect`、`quota window rollover`、`quota mode switched`、
`quota state evicted`、`quota evictor sweep`、`idempotency decision`、
`idempotency expired`、`bulkhead decision`、`guard decision`、`guard response`，
均包含输入（租户/键/内容指纹/时间）与判定依据（口径、计数、槽位、窗口/区间、首次指纹）。
