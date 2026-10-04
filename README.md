# Tenant API Guard（多租户接口保护与一致性）

在 Spring Boot Web 服务之上提供三层、按租户维度的确定性保护：

1. **配额（Quota）**：按时间窗口限制每租户请求量，并发下不超额、计数不错乱，窗口切换不重计不漏计；
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
配额 tryAcquire（当前时间窗口计数 +1 的判定）
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

## 2. 配额模型（固定时间段 / 滑动，两种口径按租户可选）

配额有两种口径，按租户各自选择、可同时并存，互不惊扰：

- **FIXED 固定时间段（默认，既有行为）**：窗口由 `floor(now / windowMs)` 划分，
  窗口序号、起止时间会出现在日志与拒绝明细中。未配置 `window-mode` 的租户行为与旧版完全一致；
- **SLIDING 滑动**：以放行请求的时间戳集合判定，**任意一段长度为 `windowMs` 的连续时间内，
  放行的总量都不超过配额**。消除了固定窗口交界处“前段末尾 + 后段开头卡两波、
  短时间放进接近两倍量”的问题。时间戳 `t` 在 `now - windowMs >= t` 时滑出区间、不再占额度。

确定性保证：

- **每个租户独立状态与锁**：计数/时间戳的读写与窗口切换在同一把锁内原子完成。
  并发 N 个请求、配额 L 时，结果严格为“恰好 L 个放行 + N−L 个 `QUOTA_EXCEEDED`”，
  与线程交错顺序无关；
- **压在交界上的并发是确定的**：FIXED 口径保留最近两个窗口的独立计数，
  晚到的旧窗口请求计入旧窗口、绝不触发状态回退清零——一半请求压在窗口最后一毫秒、
  一半压在下一窗口第一毫秒时，放行总数确定等于两个窗口各自配额之和；
  SLIDING 口径下交界两侧请求落在同一滑动区间内，放行总数确定等于配额；
- **配置平滑生效**：FIXED 在新窗口首次创建时重新读取配额配置，不回溯当前窗口；
  SLIDING 无窗口边界，配置调整对后续请求立即生效。运行期改某租户配置不影响其他租户；
- 配额只在请求进入时判定一次；只读查询见第 3 节；
- 本地内存模型：状态按租户隔离，单租户异常不会破坏其他租户的计数。

---

## 3. 配额用量只读查询（运维口子）

`GET /ops/quota/{tenant}`（不挂在 `/api/**` 下，不经过入口守卫、无需租户头）：

```json
{
  "success": true,
  "data": {
    "tenant": "t1",
    "mode": "FIXED",
    "windowIndex": 12345,
    "segmentStartMs": 12345000,
    "segmentEndMs": 12346000,
    "consumed": 3,
    "limit": 5,
    "remaining": 2,
    "wouldPermit": true,
    "reason": null
  }
}
```

- `mode`：该租户当前生效的口径；`segmentStartMs/segmentEndMs`：判定所依据的时间区段
  （FIXED 为当前窗口起止，SLIDING 为 `(now-windowMs, now]`）；
- `consumed/limit/remaining`：该区段已用、上限、剩余；`wouldPermit/reason`：
  同一时刻来一个真实请求会放行还是拒绝、拒绝时的稳定原因码；
- **纯读**：不计数、不触发窗口切换、不清理时间戳、不刷新活跃时间。
  查询与真实判定共用同一套逻辑与同一把租户锁，因此同一时刻的查询结论与
  紧随其后的真实请求结论一致（中间无其他请求时）。

---

## 4. 不活跃状态回收

服务长期运行时，很久不来的租户留下的配额运行时状态会被周期性收回：

- 由 `QuotaStateSweeper` 按 `sweep-interval-ms` 间隔触发；
- 仅当租户空闲时长达到 **max(`state-idle-ms`, `window-ms`)** 才回收——此时固定窗口早已翻篇、
  滑动时间戳全部失效，状态不再携带任何影响判定的信息，**回收对活跃租户完全透明**；
- 检查与摘除在同一租户锁内完成；并发的 `tryAcquire` 若拿到刚被摘除的状态会自动重取新状态，
  不会丢计数；
- 回收日志关键字：`quota state reclaimed`、`quota sweep`。

---

## 5. 幂等语义

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

## 6. 舱壁与降级

- 每租户有独立的并发槽计数，`tryAcquire` **完全非阻塞、不排队**：满了立即 `BULKHEAD_FULL`；
- 获取顺序“先租户槽、后全局信号量”：全局耗尽时回滚租户槽并返回 `RESOURCE_PRESSURE(503)`，
  这是明确的降级结论而非静默丢弃；
- 槽位只在请求真正占有时由 `afterCompletion` 成对释放；
- 并发上限配置对新请求立即生效，不强制中断在途请求。

---

## 7. 配置项（`application.properties`，前缀 `tenant.guard`）

| 配置项 | 默认值 | 含义 |
|---|---|---|
| `window-ms` | 1000 | 配额周期长度（毫秒），两种口径共用 |
| `default-quota` | 10 | 未单独配置租户的每周期配额；**<=0 表示默认拒绝** |
| `idempotency-ttl-ms` | 300000 | 幂等记录存活时长；过期后同键视为首次 |
| `default-max-concurrent` | 20 | 未单独配置租户的并发槽上限；<=0 表示默认拒绝 |
| `global-max-concurrent` | 1024 | 全局并发信号量上限（本地资源降级阈值） |
| `in-progress-timeout-ms` | 10000 | 并发重复请求等待首次完成的预算 |
| `state-idle-ms` | 600000 | 配额状态不活跃回收阈值；实际生效为 max(state-idle-ms, window-ms) |
| `sweep-interval-ms` | 60000 | 不活跃状态定时回收的执行间隔 |
| `tenants.<id>.quota` | — | 指定租户每周期配额；FIXED 下个窗口生效，SLIDING 立即生效 |
| `tenants.<id>.max-concurrent` | — | 指定租户并发槽上限 |
| `tenants.<id>.window-mode` | FIXED | 配额口径：`FIXED`（默认，兼容旧行为）或 `SLIDING` |

示例：

```properties
tenant.guard.tenants.t1.quota=5
tenant.guard.tenants.t1.max-concurrent=3
tenant.guard.tenants.t2.quota=100
tenant.guard.tenants.t2.max-concurrent=10
tenant.guard.tenants.t2.window-mode=SLIDING
```

**兼容范围**：不配置 `window-mode` 的租户一律走 FIXED，判定行为、拒绝明细、
配置生效时机与旧版完全一致；新旧口径可在一个实例内并存，各租户互不影响。

---

## 8. HTTP 用法

```bash
# 写操作（三重保护 + 幂等）
curl -s -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: t1' -H 'X-Idempotency-Key: order-001' \
  -H 'Content-Type: application/json' \
  -d '{"product":"widget","quantity":2}'

# 只读操作（配额 + 舱壁保护）
curl -s http://localhost:8080/api/hello/bob -H 'X-Tenant-Id: t2'

# 运维只读查询：某租户当前配额用量（无需租户头，不占额度）
curl -s http://localhost:8080/ops/quota/t2
```

成功写响应：`{"success":true,"data":{"order":"...","replay":false,"idempotencyExpireAtMs":...}}`；
重复提交时 `replay=true` 且 `order` 与首次逐字一致。
拒绝响应：`{"success":false,"error":{"reason":"QUOTA_EXCEEDED","message":...,"detail":{...}}}`。

---

## 9. 本地验证

```bash
mvn test
```

测试清单（全部为确定性并发/边界测试，日志均打印输入与判定依据）：

| 测试类 | 覆盖点 |
|---|---|
| `QuotaServiceConcurrencyTest` | 50 并发/配额 5 恰好放行 5；窗口边界 999→1000 切换、两窗口各计满；租户隔离；未配置租户拒绝 |
| `SlidingWindowQuotaTest` | 滑动口径交界处不放行双倍；时间戳恰好在 now−windowMs 滑出的边界精确性；长空闲后重新计数 |
| `MixedWindowModeTest` | FIXED/SLIDING 并存各按各口径判定；运行期改某租户配置立即生效且不牵连其他租户 |
| `BoundaryConcurrencyDeterminismTest` | 一半请求压 999、一半压 1000 的并发下，两种口径放行总数均确定且多轮重放一致 |
| `QuotaStateSweepTest` | 不活跃状态按阈值回收；窗口未翻篇前绝不回收；回收前后判定结论一致、活跃租户不受影响 |
| `QuotaUsageQueryTest` | 只读查询与真实判定结论一致；查询不计数、不触发窗口切换；未配置租户原因一致 |
| `OpsEndpointTest` | `/ops/quota/{tenant}` 无需租户头可读；查询不占额度；读数与真实流量一致；SLIDING 口径可见 |
| `IdempotencyConcurrencyTest` | 20 并发同键仅 1 次副作用且结果一致；完成后/执行中同键不同内容 CONFLICT；等待超时 IN_PROGRESS；TTL 边界 1999/2000；业务失败可重试 |
| `BulkheadTest` | 舱壁打满确定拒绝与释放回收；单租户突发不影响他人；20 并发/3 槽严格成立 |
| `WebGuardIntegrationTest` | 缺租户头、未配置租户、正常通过、幂等重放/缺键、内容冲突 409、配额打满 429 的端到端原因码 |
| `ApplicationTests` | Spring 上下文装配 |

运行期判定日志关键字：`quota decision`、`quota window rollover`、`quota usage`、
`quota state reclaimed`、`quota sweep`、`idempotency decision`、
`idempotency expired`、`bulkhead decision`、`guard decision`、`guard response`，
均包含输入（租户/键/内容指纹/时间/口径）与判定依据（计数、槽位、窗口或滑动区段、首次指纹）。
