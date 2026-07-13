# 领域与集成事件目录

## 1. 事件规范

事件使用过去时、版本后缀和稳定 envelope：

```text
id, type, version, occurredAt, tenantId,
correlationId, causationId, producer,
subjectType, subjectId, payload, metadata
```

- `event.id` 全局唯一；
- 时间为 UTC；
- payload 只含消费者稳定需要的数据；
- 不含令牌、密码、成本/毛利（除非严格内部且有明确权限边界）、完整个人信息；
- 不兼容变化发布新版本；
- 事件不是远程过程调用，消费者不能依赖其同步响应。

## 2. 事件清单

### Identity & Access

| 事件 | 触发 | 主要消费者 |
|---|---|---|
| `TenantActivatedV1` | 租户启用 | audit/reporting |
| `TenantSuspendedV1` | 租户停用 | audit/reporting, notification |
| `UserAccessChangedV1` | 本地角色/权限变更 | audit/reporting |

### Partner

| 事件 | 关键 payload | 消费者 |
|---|---|---|
| `PartnerSubmittedForReviewV1` | partnerId, number, submittedBy | notification, audit |
| `PartnerActivatedV1` | partnerId, number, eligibilityVersion | quotation read cache, audit |
| `PartnerChangesRequestedV1` | partnerId, safeReason | notification, audit |
| `PartnerRejectedV1` | partnerId, safeReason | notification, audit |
| `PartnerSuspendedV1` | partnerId, safeReason | quotation guard cache, audit |

### Catalog

| 事件 | 关键 payload | 消费者 |
|---|---|---|
| `SkuActivatedV1` | skuId, code, version | search projection, audit |
| `SkuDeactivatedV1` | skuId, code | search projection, audit |
| `SkuDisplayDataChangedV1` | skuId, version | search projection；不重写交易快照 |

### Quotation

| 事件 | 触发 |
|---|---|
| `QuotationDraftCreatedV1` | 创建草稿 |
| `QuotationSubmittedV1` | 完整校验通过 |
| `QuotationApprovalRequestedV1` | 触发人工审批 |
| `QuotationApprovedV1` | 人工或自动批准 |
| `QuotationChangesRequestedV1` | 退回修改 |
| `QuotationIssuedV1` | 发送给客户 |
| `QuotationAcceptedV1` | 客户接受；包含订单创建所需不可变快照 |
| `QuotationRejectedByCustomerV1` | 客户拒绝 |
| `QuotationExpiredV1` | 过期 |
| `QuotationConvertedV1` | 订单链接成功 |

`QuotationAcceptedV1` payload 最少包括：报价/修订 ID、业务编号、客户快照、行项目快照、金额、币种、付款条款、路径决策快照、接受时间和幂等摘要。不得迫使 Order 回查 Quotation 数据库。

实现约定：幂等摘要放在受控 envelope `metadata.idempotencyDigest`；v1 payload 兼容增加签发条款版本、请求交付日期和交付地址，生产者始终填充，旧事件消费者仍可按可选字段安全读取。

### Trade Planning

| 事件 | 用途 |
|---|---|
| `TradeRouteEvaluatedV1` | 审计/指标，可不作为核心编排触发 |
| `TradeRouteOverriddenV1` | 记录推荐与选择、actor、reason、policyVersion |
| `RoutePolicyPublishedV1` | 配置审计 |

### Trade Order

| 事件 | 消费者 |
|---|---|
| `TradeOrderCreatedV1` | inventory, audit, reporting |
| `TradeOrderReservationConfirmedV1` | reporting, notification |
| `TradeOrderReservationFailedV1` | exception, notification, reporting |
| `TradeOrderCancellationRequestedV1` | inventory, fulfillment |
| `TradeOrderCancelledV1` | settlement, audit |
| `TradeOrderFulfilledV1` | settlement, reporting |

### Inventory

| 事件 | 消费者 |
|---|---|
| `InventoryReservationConfirmedV1` | order, fulfillment, reporting |
| `InventoryReservationFailedV1` | order, exception, reporting |
| `InventoryReservationReleasedV1` | order, reporting |
| `InventoryReservationConsumedV1` | order, reporting |
| `InventoryLotAdjustedV1` | audit, reporting |

### Fulfillment

| 事件 | 消费者 |
|---|---|
| `FulfillmentPlanCreatedV1` | order, reporting |
| `FulfillmentStepReadyV1` | notification |
| `FulfillmentStepStartedV1` | reporting |
| `FulfillmentStepCompletedV1` | reporting, subsequent step orchestration |
| `FulfillmentStepFailedV1` | exception, reporting |
| `FulfillmentStepOverdueV1` | exception, notification |
| `PublicMilestoneReachedV1` | customer timeline, reporting |
| `FulfillmentCompletedV1` | order, settlement, reporting |

### Exception Center

| 事件 | 消费者 |
|---|---|
| `ExceptionOpenedV1` | notification, reporting |
| `ExceptionAssignedV1` | notification, audit |
| `ExceptionRecoveryRequestedV1` | source module adapter/orchestrator |
| `ExceptionResolvedV1` | reporting |
| `ExceptionClosedV1` | reporting |

### Settlement

| 事件 | 消费者 |
|---|---|
| `ReceivableCreatedV1` | notification, reporting |
| `PaymentRecordedV1` | reporting, audit |
| `PaymentReversedV1` | reporting, audit |
| `ReceivableOverdueV1` | notification, reporting |
| `ReceivablePaidV1` | order/reporting |

## 3. 发布级别

| 类型 | 机制 | 适用 |
|---|---|---|
| 聚合内部事件 | 聚合收集，事务内由应用发布 | 同模块副作用 |
| 模块事件 | Spring Modulith 可靠事件发布 | 同一应用内跨模块协作 |
| 外部集成事件 | Outbox/发布适配器 → Kafka full profile | 演示外部消费者/解耦 |

P1 可以使同一事实映射为模块事件和外部事件，但要保留一个语义源，不能在多个模块手工构造不同含义。

## 4. 幂等消费者规则

- 事务开始先插入 Inbox `(consumer, event_id)`；
- 唯一冲突表示重复，返回既有处理结果；
- Inbox 与业务副作用在同一事务提交；
- 失败不将 Inbox 标为成功；
- 处理结果/错误摘要可观测；
- 重放权限受控并写审计；
- 事件顺序不能全局假设，只在同一 subject/partition key 下尽量保持。

## 5. 事件兼容性

允许：新增可选字段、扩大描述文本但不改变语义、发布新事件类型。

不允许：重命名/删除字段、改变枚举含义、把金额单位从主要单位改最小单位、不改变版本就改变 subject、把敏感字段加入既有公开事件。

AsyncAPI 和 JSON Schema 为机器可读权威契约。
