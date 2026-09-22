# Tenant API Guard(多租户接口保护与一致性)

在 Spring Boot Web 服务之上提供三类**确定性、可解释**的多租户保护能力:

1. **按租户配额控制**(滑动窗口、可按租户配置、并发安全)
2. **写操作幂等**(并发重复提交只生效一次、重复请求回放一致结果、内容冲突明确拒绝)
3. **异常流量隔离与可控降级**(租户并发隔离舱 + 全局容量上限,达到上限以稳定原因拒绝,不静默丢弃)

所有判定都只依赖租户自身的请求序列与单调时间,同输入必得同结论,拒绝原因稳定可区分。

---

## 1. 快速开始

```bash
mvn spring-boot:run
```

受保护接口统一位于 `/api/**`:

| 方法 & 路径 | 必要请求头 | 说明 |
|---|---|---|
| `GET /api/ping` | `X-Tenant-Id` | 读接口:受隔离舱 + 配额保护 |
| `POST /api/orders` | `X-Tenant-Id`、`Idempotency-Key` | 写接口:额外受幂等保护 |

成功响应:

```json
{ "success": true, "data": { "orderId": 1, "received": {} }, "errorCode": null, "errorMessage": null }
```

拒绝响应(统一结构,HTTP 状态码与错误码稳定):

```json
{ "success": false, "data": null, "errorCode": "QUOTA_EXCEEDED", "errorMessage": "租户在当前时间窗口内超出请求配额" }
```

---

## 2. 配额模型(滑动窗口)

- 窗口长度 `W` 被切分为 `B` 个等长子桶,桶长 `d = floor(W / B)`,有效窗口长度为 `d*B`。
- 每个租户独立维护一个**以完整桶序号(`floor(now/d)`)为键**的计数器。
- 一次 `tryAcquire` 在该租户的监视器锁内**原子完成「滑动 + 统计 + 判定 + 自增」**:
  - 丢弃序号严格小于 `currentBucket - B` 的旧桶;
  - 统计 `currentBucket - B <= b <= currentBucket` 范围内所有桶的计数(最多 `B+1` 个桶);
  - `used >= limit` 则拒绝,否则当前桶计数 +1 后放行。
- 判定是**保守(只少放、不多放)**的:在窗口边界处可能把一个仅余少量重叠、正在滚出的旧桶
  仍然计入,因此边界处最多少量拒绝一次,但**永不超额放行**。
- 以完整桶序号为键而非环形数组下标,杜绝了桶序号相差 `B` 整数倍时的槽位别名冲突;
  窗口切换不重复计数、不漏计数。
- 租户之间使用各自的锁,互不阻塞。配额与并发许可一旦授予即不退还(下游业务失败也计入
  租户本窗口用量),使结论只取决于租户自身请求序列,可复现、可解释。

拒绝:HTTP `429`,`errorCode=QUOTA_EXCEEDED`,并带 `Retry-After`(秒,建议等待到下一桶边界)。

---

## 3. 幂等语义

- 作用于写方法:`POST/PUT/PATCH/DELETE`,必须携带 `Idempotency-Key`。
- 存储键为 **(租户, 幂等标识)**,不同租户互不可见。
- 请求内容以**原始请求体的 SHA-256 摘要**作为一致性指纹(过滤器先缓存请求体,控制器仍可正常读取)。
- 每条记录是一个小状态机:

| 状态 | 并发行为 |
|---|---|
| **在途 in-flight** | 首次请求正在执行;并发的相同请求在分段锁外**有界等待**其结果,等待期间不执行任何业务副作用 |
| **已完成** | 保存响应快照(状态码、响应体、Content-Type);重复提交原样回放,并加响应头 `X-Idempotent-Replay: true` |
| **冲突** | 相同 `Idempotency-Key` 但内容摘要不同:立即 HTTP `409`,`errorCode=IDEMPOTENCY_CONFLICT`;冲突请求不会污染既有记录 |

- **只产生一次实际效果**:首次请求 `New` 才进入控制器;等待者通过 `CompletableFuture` 拿到
  首次的响应快照,得到与首次**完全一致**的结果(含相同业务标识,例如同一个 `orderId`)。
- **失败处理**:首次请求抛出异常或返回 5xx 时释放在途记录,后续相同请求可作为全新提交重试
  (避免一个永久卡住的在途请求锁死该 key)。
- **过期语义(确定且可解释)**:已完成记录存活**至少** `ttl-millis`;
  - `now - createdAt < ttl` 内:重复提交一律回放首次结果;
  - `now - createdAt >= ttl`:该记录视为过期,被惰性清理(新请求到来时)或定时清理;
    清理后同一标识等价于**全新提交,可能再次产生业务效果**。这是明确的语义,使用方应将
    `ttl-millis` 设为大于业务上需要去重的最长时间。
- **容量保护**:记录数达到 `max-records` 时先尝试逐出过期记录;仍不足则 HTTP `503`、
  `errorCode=IDEMPOTENCY_STORE_FULL`,**不静默丢弃**。
- 在途等待超过 `inflight-timeout-millis` 时返回 HTTP `504`、
  `errorCode=IDEMPOTENCY_INFLIGHT_TIMEOUT`(明确告知"首次仍在处理"而非不确定结果)。

---

## 4. 异常流量隔离与可控降级

- **单租户隔离舱**:每个租户一个独立 `Semaphore`,突发流量只消耗自身许可,达到上限立即
  HTTP `429`、`errorCode=TENANT_BURST_LIMITED`(非阻塞,不排队)。
- **全局容量兜底**:另有一个全局 `Semaphore` 限制本地总在处理并发,达到上限立即
  HTTP `503`、`errorCode=GLOBAL_CAPACITY_LIMITED`。
- 获取顺序:先租户后全局;获取全局失败会归还租户许可。释放幂等(只生效一次)。
- 因租户信号量相互独立,**单个租户的异常请求/突发不会影响其他租户的判定**。
- 所有上限触发都是**显式、可区分、可观测**的拒绝,绝不静默丢弃或返回不确定结论。

### 稳定错误码总览

| HTTP | errorCode | 含义 |
|---|---|---|
| 400 | `TENANT_MISSING` | 缺少 `X-Tenant-Id` |
| 400 | `IDEMPOTENCY_KEY_MISSING` | 写操作缺少 `Idempotency-Key` |
| 429 | `QUOTA_EXCEEDED` | 超出滑动窗口配额(带 `Retry-After`) |
| 409 | `IDEMPOTENCY_CONFLICT` | 同标识不同内容 |
| 504 | `IDEMPOTENCY_INFLIGHT_TIMEOUT` | 等待首次在途请求结果超时 |
| 429 | `TENANT_BURST_LIMITED` | 单租户并发达到隔离上限 |
| 503 | `GLOBAL_CAPACITY_LIMITED` | 全局并发容量达到上限 |
| 503 | `IDEMPOTENCY_STORE_FULL` | 幂等记录数达到上限 |

---

## 5. 可配置项

前缀 `tenant.guard`,均支持本地 `application.properties` / 环境变量覆盖。

### 配额 `tenant.guard.quota.*`

| 键 | 默认值 | 说明 |
|---|---|---|
| `default-limit` | 100 | 每租户每窗口默认请求数 |
| `window-millis` | 60000 | 窗口长度(毫秒) |
| `buckets-per-window` | 6 | 窗口子桶数,越大越平滑;桶长 = window / buckets |
| `tenant-limits.<tenantId>` | — | 按租户覆盖配额 |

### 幂等 `tenant.guard.idempotency.*`

| 键 | 默认值 | 说明 |
|---|---|---|
| `ttl-millis` | 86400000(24h) | 幂等记录保留时长 |
| `inflight-timeout-millis` | 5000 | 并发重复请求等待首次完成的上限 |
| `max-records` | 100000 | 本地幂等记录硬上限 |
| `lock-stripes` | 64 | 分段锁数量 |
| `purge-interval-millis` | 60000 | 过期记录定时清理周期(`<=0` 关闭,仅惰性清理) |

### 隔离舱 `tenant.guard.bulkhead.*`

| 键 | 默认值 | 说明 |
|---|---|---|
| `global-max-concurrent` | 1000 | 全局最大在处理并发 |
| `default-tenant-max-concurrent` | 100 | 单租户默认最大并发 |
| `tenant-max-concurrent.<tenantId>` | — | 按租户覆盖单租户并发 |
| `fair` | true | 信号量是否公平 |

> 说明:当前实现为**单机、内存态**(适合本地验证与单节点部署);配额与幂等状态不跨实例共享。
> 多实例部署需替换为共享存储(Redis 等),错误码与判定语义可保持不变。

---

## 6. 本地验证

### 运行全部测试

```bash
mvn test
```

共 16 个测试,分三层:

- **配额单元测试** `tests/quota`
  - `QuotaServiceConcurrencyTest`:200 线程 / 配额 20,断言**恰好**放行 20、其余稳定拒绝;
    拒绝原因与 `Retry-After` 稳定。
  - `QuotaWindowBoundaryTest`:可控时钟,覆盖桶边界与窗口边界(不重复计数、不漏计数、保守不超放)。
- **幂等单元测试** `tests/idempotency`
  - `IdempotencyServiceTest`:32 并发重复提交仅 1 次副作用且回放一致;同标识不同内容冲突;
    TTL 边界过期语义;容量打满 `IDEMPOTENCY_STORE_FULL`;在途超时 `IDEMPOTENCY_INFLIGHT_TIMEOUT`。
- **Web 端到端** `tests/web/GuardFilterIntegrationTest`:真实 Spring MVC + 过滤器,
  覆盖并发超配额、并发幂等下单、同标识不同内容、租户突发隔离不影响其他租户、缺少头的拒绝。

每个用例都通过 SLF4J 打印**输入**(租户、标识、序号、内容摘要、时间)与**判定依据**
(allowed、used、limit、retryAfter、outcome、status、replay 标记),便于复盘。

查看带日志的单次运行:

```bash
mvn -Dtest=QuotaWindowBoundaryTest test
mvn -Dtest=IdempotencyServiceTest test
mvn -Dtest=GuardFilterIntegrationTest test
```

### 手工验证

```bash
# 1) 正常读
curl -s -H 'X-Tenant-Id: demo' http://localhost:8080/api/ping

# 2) 写下单(同一 Idempotency-Key 重复执行,返回相同 orderId,第二次带 X-Idempotent-Replay)
curl -s -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: demo' -H 'Idempotency-Key: key-1' \
  -H 'Content-Type: application/json' -d '{"item":"book"}'
curl -i -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: demo' -H 'Idempotency-Key: key-1' \
  -H 'Content-Type: application/json' -d '{"item":"book"}'

# 3) 相同 key 不同内容 -> 409 IDEMPOTENCY_CONFLICT
curl -i -X POST http://localhost:8080/api/orders \
  -H 'X-Tenant-Id: demo' -H 'Idempotency-Key: key-1' \
  -H 'Content-Type: application/json' -d '{"item":"phone"}'

# 4) 连续超过配额(默认 100)后 -> 429 QUOTA_EXCEEDED + Retry-After
for i in $(seq 1 105); do curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Tenant-Id: demo' http://localhost:8080/api/ping; done | sort | uniq -c
```

测试专用配置在 `src/test/resources/application.properties`(小配额、短窗口,便于确定性验证)。
