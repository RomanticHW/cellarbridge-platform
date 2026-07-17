# 错误码目录

## 1. 规则

错误码为稳定大写蛇形，不把 Java 异常名作为对外 code。相同业务含义保持同一码；detail 可本地化。HTTP 状态只表达类别，客户端按 `code` 做恢复。

## 2. 通用

| Code | HTTP | Retryable | 含义 |
|---|---:|---:|---|
| `VALIDATION_FAILED` | 400 | 否 | 一个或多个字段不合法 |
| `MALFORMED_REQUEST` | 400 | 否 | JSON/类型/格式错误 |
| `AUTHENTICATION_REQUIRED` | 401 | 否 | 缺失认证 |
| `INVALID_ACCESS_TOKEN` | 401 | 可能 | token 无效/过期 |
| `ACCESS_DENIED` | 403 | 否 | 权限不足 |
| `RESOURCE_NOT_FOUND` | 404 | 否 | 当前租户/权限范围内不存在 |
| `RESOURCE_VERSION_CONFLICT` | 412 | 否 | expectedVersion/ETag 不匹配 |
| `PRECONDITION_REQUIRED` | 428 | 否 | 需要 If-Match |
| `INVALID_STATE_TRANSITION` | 409 | 否 | 当前状态不允许动作 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 否 | 缺少幂等键 |
| `IDEMPOTENCY_KEY_REUSED` | 409 | 否 | 同 key 不同 payload |
| `RATE_LIMITED` | 429 | 是 | 超出限制 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 是 | 临时依赖故障 |
| `INTERNAL_ERROR` | 500 | 可能 | 未分类服务错误，detail 安全 |

## 3. Partner

| Code | HTTP | 含义 |
|---|---:|---|
| `PARTNER_DUPLICATE_IDENTIFIER` | 409 | 识别号重复 |
| `PARTNER_POTENTIAL_DUPLICATE` | 409 | 需处理潜在重复 |
| `PARTNER_PROFILE_INCOMPLETE` | 422 | 提交资料不完整 |
| `PARTNER_NOT_ACTIVE` | 409 | 客户不可交易 |
| `PARTNER_REVIEWER_CONFLICT` | 409 | 提交人不能审核自己 |
| `PARTNER_ROUTE_NOT_ELIGIBLE` | 409 | 无指定路径资格 |

## 4. Catalog/Inventory

| Code | HTTP | 含义 |
|---|---:|---|
| `SKU_NOT_ACTIVE` | 409 | SKU 已停用 |
| `SKU_DUPLICATE_DEFINITION` | 409 | SKU 维度重复 |
| `SUPPLY_NOT_AUTOMATICALLY_RESERVABLE` | 409 | 需人工确认 |
| `INVENTORY_INSUFFICIENT` | 409 | 可用量不足 |
| `INVENTORY_FIXED_POOL_INELIGIBLE` | 409 | 固定供给池与路线、类型、SKU 或单位不匹配 |
| `INVENTORY_ALLOCATION_CONFLICT` | 409 | 候选批次在原子更新时不再满足分配条件 |
| `SUPPLY_DECISION_MISSING` | 409 | 旧事件未携带 Supply Decision，需显式处理 |
| `RESERVATION_REQUEST_CONFLICT` | 409 | 同一订单出现不同的不可变预留请求 |
| `INVENTORY_RESERVATION_ALREADY_FINAL` | 409/200 | 已有终态，按幂等返回 |

事件终态错误另含 `QUOTATION_ACCEPTED_SUPPLY_DECISION_INVALID`、`QUOTATION_ACCEPTED_SUPPLY_DECISION_HASH_MISMATCH` 与 `ORDER_SUPPLY_DECISION_CONFLICT`；它们不是 HTTP 错误码。
| `INVENTORY_ALLOCATION_CONFLICT` | 409 | 并发/批次状态变化，可能受控重试 |
| `INVENTORY_RELEASE_EXCEEDS_RESERVED` | 409 | 释放超出可释放 |
| `INVENTORY_CONSUMPTION_EXCEEDS_RESERVED` | 409 | 消费超出预占 |

## 5. Quotation/Trade Planning

| Code | HTTP | 含义 |
|---|---:|---|
| `QUOTE_EMPTY` | 422 | 无报价行 |
| `QUOTE_LINE_DUPLICATE_SKU` | 409 | 同 SKU 重复行 |
| `QUOTE_PRICE_STALE` | 409 | 定价输入需刷新 |
| `QUOTE_HAS_NO_ELIGIBLE_ROUTE` | 422 | 无可用路径 |
| `QUOTE_ROUTE_NOT_ELIGIBLE` | 409 | 选择路径无效 |
| `QUOTE_ROUTE_OVERRIDE_REASON_REQUIRED` | 422 | 覆盖原因缺失 |
| `QUOTE_SUPPLY_DECISION_REQUIRED` | 422 internal / 409 public | 当前修订缺少可验证的冻结供给决策 |
| `QUOTE_SUPPLY_DECISION_CONFLICT` | 409 | Quotation 副本与 Planning、Root/JSON 或行证据冲突 |
| `QUOTE_APPROVAL_REQUIRED` | 409 | 未批准不能发送 |
| `QUOTE_NOT_ISSUABLE` | 409 | 当前状态不可发送 |
| `QUOTE_NOT_ACCEPTABLE` | 409 | 当前状态不可接受 |
| `QUOTE_EXPIRED` | 409 | 到期 |
| `QUOTE_WITHDRAWN` | 409 | 已撤销 |
| `QUOTE_ALREADY_DECIDED` | 409/200 | 已接受/拒绝，按幂等语义 |
| `QUOTE_TERMS_VERSION_MISMATCH` | 409 | 客户确认的条款版本与当前签发修订不一致 |
| `ROUTE_POLICY_NOT_FOUND` | 409 | 无适用策略 |
| `ROUTE_POLICY_INVALID` | 500 | 已发布策略不合法（严重配置错误） |

路径拒绝 reason code（不是 API 错误）：

`PARTNER_NOT_ELIGIBLE`, `DESTINATION_NOT_SUPPORTED`, `SUPPLY_TYPE_NOT_SUPPORTED`, `DELIVERY_DATE_UNACHIEVABLE`, `MOQ_NOT_MET`, `PAYMENT_TERM_NOT_ALLOWED`, `CURRENCY_NOT_SUPPORTED`, `NO_PROMISABLE_SUPPLY`。

## 6. Order/Fulfillment/Exception

| Code | HTTP | 含义 |
|---|---:|---|
| `ORDER_SOURCE_QUOTE_CONFLICT` | 500 | 相同 quote ID 快照不一致 |
| `ORDER_CANNOT_BE_CANCELLED` | 409 | 当前阶段不允许取消 |
| `ORDER_RESERVATION_PENDING` | 409 | 操作需等待预占 |
| `FULFILLMENT_TEMPLATE_NOT_FOUND` | 409 | 无路径模板 |
| `FULFILLMENT_DEPENDENCY_NOT_MET` | 409 | 前置步骤未完成 |
| `FULFILLMENT_STEP_ALREADY_FINAL` | 409/200 | 步骤终态 |
| `FULFILLMENT_ACTION_NOT_ALLOWED` | 409 | 模板不允许动作 |
| `EXCEPTION_ALREADY_OPEN` | 409/200 | 已有相同开放异常 |
| `EXCEPTION_RECOVERY_NOT_ALLOWED` | 409 | 当前类型/状态不允许恢复 |
| `EXCEPTION_SOURCE_NOT_RECOVERED` | 409 | 源对象未恢复不能关闭 |

## 7. Settlement

| Code | HTTP | 含义 |
|---|---:|---|
| `RECEIVABLE_ALREADY_EXISTS` | 409/200 | 业务触发已创建 |
| `PAYMENT_AMOUNT_EXCEEDS_OUTSTANDING` | 409 | 超过余额 |
| `PAYMENT_REFERENCE_REUSED` | 409 | 相同参考号不同内容 |
| `PAYMENT_ALREADY_REVERSED` | 409/200 | 已冲正 |
| `PAYMENT_CURRENCY_MISMATCH` | 409 | 币种不一致 |

## 8. 事件/运维

| Code | 含义 |
|---|---|
| `EVENT_SCHEMA_INVALID` | schema 不通过 |
| `EVENT_UNSUPPORTED_VERSION` | 消费者不支持版本 |
| `EVENT_HANDLER_RETRY_EXHAUSTED` | 自动重试耗尽 |
| `EVENT_REPLAY_NOT_ALLOWED` | 无权限/状态不允许重放 |
| `PROJECTION_REBUILD_IN_PROGRESS` | 投影重建中 |

## 9. 字段错误

`errors[]`：

- `field`：JSON pointer 或字段路径；
- `code`：如 `REQUIRED`, `OUT_OF_RANGE`, `INVALID_FORMAT`；
- `message`：用户友好；
- `rejectedValue` 仅在安全且不敏感时返回。

## 10. 维护

新增错误码必须更新本目录、OpenAPI components、前端映射和测试；不删除已发布码，废弃需版本说明。
