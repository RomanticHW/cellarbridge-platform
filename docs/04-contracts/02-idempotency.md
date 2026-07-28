# 幂等设计

## 1. 目标

网络重试、双击、事件至少一次投递和进程恢复不能重复创建订单、预占、付款或恢复副作用。

幂等不等于互斥，也不等于乐观并发。它识别“同一业务意图”。

## 2. 幂等类型

### 业务自然键

- quote → order：`(tenant_id, source_quotation_id)`；
- order → reservation：`(tenant_id, order_id)`；
- order → fulfillment plan：`(tenant_id, order_id, template_version)`；
- receivable business identity：`(tenant_id, order_id, trigger_policy_code, trigger_policy_version)`；
- receivable trigger delivery：`(tenant_id, trigger_type, trigger_id)`；
- exception open：source + type + open scope。

### 客户端 Idempotency-Key

用于客户接受、登记付款、恢复动作等可能重试的 HTTP 命令。

### 事件 ID

消费者 Inbox `(consumer_name, event_id)`。

## 3. HTTP 目标协议

本节定义各写用例按需采用的目标协议，不代表每个当前端点已经实现持久化 `PROCESSING`、
status URI 或租约恢复。已实现边界以对应模块合同、实现状态和集成测试为准。

请求头：

```text
Idempotency-Key: <UUID or 20-200 safe characters>
```

服务器计算 request hash，包括：规范化方法、资源/业务目标、关键 body、当前 tenant/actor scope；不包括 trace header。

处理：

1. 查/插入 `(tenant, operation, key)`；
2. 新 key 标记 PROCESSING；
3. 同 key+hash：
   - COMPLETED 返回原 status/body/resource；
   - PROCESSING 返回 409/202 并提供 status URI；
   - FAILED_RETRYABLE 按策略重试；
4. 同 key 不同 hash：409 `IDEMPOTENCY_KEY_REUSED`；
5. 业务副作用和 COMPLETED 结果在同事务（或可证明的原子组合）提交。

## 4. 响应

可返回：

- `Idempotency-Replayed: true`；
- 同一资源 ID；
- 原始业务状态的当前安全投影；
- 不强制逐字节复用含过期 traceId 的响应，可保存稳定 result reference。

## 5. 并发创建订单

伪流程：

```text
find by source_quote_id
if exists -> validate snapshot hash -> return
try insert order with unique source_quote_id
on unique conflict -> re-read -> validate hash -> return
```

预检查只优化，唯一约束最终保证。若 hash 不同，返回/告警 `ORDER_SOURCE_QUOTE_CONFLICT`。

## 6. 库存预占

`reservation(order_id)` 唯一。重复命令：

- CONFIRMED：返回 allocation 摘要；
- FAILED：若失败是业务终态且供给未变化，返回既有；经授权重试创建新的 attempt 但同 Reservation 聚合记录；
- PROCESSING：返回处理中；
- 不重复执行已成功 allocation。

## 7. 付款

当前付款业务幂等键为 `(tenant_id, external_reference)`；HTTP key 为同租户传输幂等键，
两者都需要。服务在事务内对 key 与 reference 获取数据库 advisory lock，再读取既有事实，
避免并发请求把唯一约束异常暴露为不稳定结果。相同 reference 与相同规范化 payload 返回当前
应收投影并带 `Idempotency-Replayed: true`；金额、币种、日期、方式、备注或目标应收不同返回
`PAYMENT_REFERENCE_REUSED`。冲正使用 `(tenant_id, Idempotency-Key hash)`，相同 payload 可重放，
不同 payload 返回 `IDEMPOTENCY_KEY_REUSED`。

## 8. 保留

- 交易自然键永久随业务记录；
- HTTP idempotency record 至少覆盖客户端最大重试窗口，演示默认 30 天；
- Event Inbox 至少覆盖事件保留/重放期，演示默认 90 天或长期保留摘要；
- 清理任务不删除业务唯一约束。

当前自动清理只作用于 `quotation.http_idempotency`。写入使用条件原子 upsert：不存在时插入，
仅在冲突记录为已过期 `COMPLETED` 时替换，受影响行数必须为 1，否则业务事务回滚。
Quotation 客户决定在同一事务直接写入 `COMPLETED`，当前不会创建 `PROCESSING` 或提供
status URI/租约恢复；若存在遗留或人工写入的 `PROCESSING`，系统不会自动替换或删除，
同 scope 写入由条件 upsert 的 affected-row guard 以稳定的 `500 INTERNAL_ERROR`
安全失败并回滚。该错误表示不应出现的持久化状态，而不是可承诺恢复时限的正常处理中响应；
因此不提供 `Retry-After` 或 `Location`。每小时任务以 `CURRENT_TIMESTAMP` 为准，使用
`FOR UPDATE SKIP LOCKED` 每批 500 条、每轮最多 10 批；客户决定、审计、付款/冲正、
Consumer Inbox 与其他业务幂等事实继续按各自合同保留。新摘要的 `expires_at` 仍由应用
注入时钟生成，部署必须维持应用与数据库时钟同步；数据库时间是清理资格的最终判断。

## 9. 安全

- key 作用域包括 tenant、operation、必要 actor/partner；
- 不信任 key 作为授权；
- key 不含敏感信息；
- 防止用同 key 探测其他租户结果；
- 记录 hash 而非完整敏感请求。

## 10. 测试

- 顺序重复；
- 20~100 并发相同 key；
- 同 key 不同 payload；
- 事务提交后响应前崩溃；
- 对实现了 `PROCESSING` 租约的用例验证过期恢复；
- 事件重复/乱序；
- 保留清理后自然键仍防重复。
