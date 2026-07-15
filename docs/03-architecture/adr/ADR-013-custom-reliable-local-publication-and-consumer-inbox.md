# ADR-013：采用自定义可靠本地发布与 Consumer Inbox

- 状态：Accepted；日期：2026-07-14

**背景与关系：** 本 ADR 澄清 ADR-007。`platform_event.event_publication`、`ReliableEventPublisher`、本地 dispatcher、`LocalEventHandler` 与 `platform_event.event_inbox` 构成 core profile 的自定义可靠事件路径，而非 Spring Modulith Event Publication Registry；Spring Modulith 继续用于模块发现、验证和模块测试。

## 决策

业务变更与完整版本化 publication 在同一本地事务提交；dispatcher 可重复投递，语义是 at-least-once，不声明 exactly-once。Consumer 以稳定的 `consumer_name + event_id` 和数据库约束去重，在一个事务内领取 Inbox、执行本模块副作用、写后继 publication、完成 Inbox 并保存安全结果摘要，任一步失败全部回滚。重复投递返回完整既有结果或无害跳过，不重复副作用且处理时间不得倒退；同 event ID 的 tenant、类型或 payload hash 不同是 binding conflict，不得覆盖可信终态、binding、结果或时间，也不得重放副作用。当前 core 保留原终态并返回 `BINDING_CONFLICT_PRESERVED`，durable anomaly/audit/failure observation 仍为 Planned。
已知永久契约、binding 或业务身份错误进入 `FAILED_FINAL`；技术瞬态及所有未显式分类的意外异常（含编程错误）进入有界退避的 `FAILED_RETRYABLE`，达到上限后才转 `FAILED_FINAL`。只有首次处理且无可信终态证据的永久错误可写本次最终失败。业务失败发布版本化业务事实；技术失败只进入 publication/inbox 状态、日志和指标，payload 不含异常堆栈。`QuotationAcceptedEventHandler` 已改用显式校验；`TradeOrderCreatedEventHandler` 对 broad `NullPointerException` 的分类仍是已知偏差，不得据此宣称全部 handler 已符合本规则。
本地 Inbox 完成不表示 Kafka 或其他 broker 已确认；未来外部发布使用独立 external-outbox adapter 和状态，broker ack、分区、重试不得与本地消费完成混用。只有出现现有机制无法满足且可迁移的明确证据，才由新 ADR 评估 Spring Modulith Registry。
**后果：** 继续维护自定义表、dispatcher、失败分类和数据库测试；后续补齐 retention、reconciliation、受权限审计保护的 manual replay，以及 backlog、失败、延迟和 duplicate 指标。手动重放不修改原事件，业务纠正通过新版本或补偿事实完成。
