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

## 2. 配额模型（固定时间窗口）

- 窗口由 `floor(now / windowMs)` 划分，窗口序号、起止时间会出现在日志与拒绝明细中；
- **每个租户独立状态与锁**：计数自增和窗口切换在同一把锁内原子完成。并发 N 个请求、
  配额 L 时，结果严格为“恰好 L 个放行 + N−L 个 `QUOTA_EXCEEDED`”；
- **窗口惰性切换**：第一个落入新窗口的请求封存旧窗口计数、清零并重新读取配置。
  旧窗口不重计、不漏计；配置在当前窗口结束后平滑生效，不回溯当前窗口；
- 配额只在请求进入时判定一次；`QuotaService.peek` 只读不计数，供运维/测试观察；
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
| `window-ms` | 1000 | 配额时间窗口长度（毫秒） |
| `default-quota` | 10 | 未单独配置租户的每窗口配额；**<=0 表示默认拒绝** |
| `idempotency-ttl-ms` | 300000 | 幂等记录存活时长；过期后同键视为首次 |
| `default-max-concurrent` | 20 | 未单独配置租户的并发槽上限；<=0 表示默认拒绝 |
| `global-max-concurrent` | 1024 | 全局并发信号量上限（本地资源降级阈值） |
| `in-progress-timeout-ms` | 10000 | 并发重复请求等待首次完成的预算 |
| `tenants.<id>.quota` | — | 指定租户每窗口配额；下个窗口平滑生效 |
| `tenants.<id>.max-concurrent` | — | 指定租户并发槽上限 |

示例：

```properties
tenant.guard.tenants.t1.quota=5
tenant.guard.tenants.t1.max-concurrent=3
tenant.guard.tenants.t2.quota=100
tenant.guard.tenants.t2.max-concurrent=10
```

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
```

成功写响应：`{"success":true,"data":{"order":"...","replay":false,"idempotencyExpireAtMs":...}}`；
重复提交时 `replay=true` 且 `order` 与首次逐字一致。
拒绝响应：`{"success":false,"error":{"reason":"QUOTA_EXCEEDED","message":...,"detail":{...}}}`。

---

## 7. 本地验证

```bash
mvn test
```

测试清单（全部为确定性并发/边界测试，日志均打印输入与判定依据）：

| 测试类 | 覆盖点 |
|---|---|
| `QuotaServiceConcurrencyTest` | 50 并发/配额 5 恰好放行 5；窗口边界 999→1000 切换、两窗口各计满；租户隔离；未配置租户拒绝 |
| `IdempotencyConcurrencyTest` | 20 并发同键仅 1 次副作用且结果一致；完成后/执行中同键不同内容 CONFLICT；等待超时 IN_PROGRESS；TTL 边界 1999/2000；业务失败可重试 |
| `BulkheadTest` | 舱壁打满确定拒绝与释放回收；单租户突发不影响他人；20 并发/3 槽严格成立 |
| `WebGuardIntegrationTest` | 缺租户头、未配置租户、正常通过、幂等重放/缺键、内容冲突 409、配额打满 429 的端到端原因码 |
| `ApplicationTests` | Spring 上下文装配 |

运行期判定日志关键字：`quota decision`、`quota window rollover`、`idempotency decision`、
`idempotency expired`、`bulkhead decision`、`guard decision`、`guard response`，
均包含输入（租户/键/内容指纹/时间）与判定依据（计数、槽位、窗口、首次指纹）。
