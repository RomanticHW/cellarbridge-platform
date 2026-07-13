# 幂等设计

## 1. 目标

网络重试、双击、事件至少一次投递和进程恢复不能重复创建订单、预占、付款或恢复副作用。

幂等不等于互斥，也不等于乐观并发。它识别“同一业务意图”。

## 2. 幂等类型

### 业务自然键

- quote → order：`(tenant_id, source_quotation_id)`；
- order → reservation：`(tenant_id, order_id)`；
- order → fulfillment plan：`(tenant_id, order_id, template_version)`；
- receivable trigger：`(tenant_id, trigger_type, trigger_id)`；
- exception open：source + type + open scope。

### 客户端 Idempotency-Key

用于客户接受、登记付款、恢复动作等可能重试的 HTTP 命令。

### 事件 ID

消费者 Inbox `(consumer_name, event_id)`。

## 3. HTTP 协议

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

外部参考号 + payer/source scope 为业务幂等键；HTTP key 为传输幂等。两者都需要。相同参考号金额/币种不同返回冲突。

## 8. 保留

- 交易自然键永久随业务记录；
- HTTP idempotency record 至少覆盖客户端最大重试窗口，演示默认 30 天；
- Event Inbox 至少覆盖事件保留/重放期，演示默认 90 天或长期保留摘要；
- 清理任务不删除业务唯一约束。

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
- PROCESSING 租约过期恢复；
- 事件重复/乱序；
- 保留清理后自然键仍防重复。
