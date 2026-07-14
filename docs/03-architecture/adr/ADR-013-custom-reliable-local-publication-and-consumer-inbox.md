# ADR-013：采用自定义可靠本地发布与 Consumer Inbox

- 状态：Accepted
- 日期：2026-07-14

## 与既有 ADR 的关系

本 ADR 澄清（Clarifies）ADR-007。ADR-007 的可靠事件、至少一次语义和 Kafka 可选外部通道结论保持不变；本 ADR 冻结 core profile 当前实际采用的本地发布、消费和失败状态协议。

## 背景

当前实现以 `platform_event.event_publication` 保存可靠本地事实，通过 `ReliableEventPublisher`、本地 dispatcher、`LocalEventHandler` 和 `platform_event.event_inbox` 完成模块间投递与消费。它不是 Spring Modulith Event Publication Registry。Spring Modulith 继续用于模块发现、结构验证和模块测试。

现有路径已经具有生产者事务原子性、Consumer Inbox 去重、业务副作用回滚、有界重试基础设施和重复消费证据。当前两个 handler 仍把宽泛捕获的 `NullPointerException` 与解析/参数错误一起转换成不可重试的契约失败；这会把编程错误错误归类为契约错误，是本 ADR 所属 correction branch 达到 Ready 前必须修正并补测试的现存偏差。在修正完成前，不得声称当前实现已经完全满足下述失败分类。

## 决策

### 发布与交付语义

- 生产者的业务变更与 `event_publication` 必须在同一个本地事务提交；`ReliableEventPublisher` 只能在既有事务内记录完整版本化事件。
- 本地 dispatcher 可重复投递，因此语义是 at-least-once delivery，不声明端到端 exactly-once。
- Consumer 以稳定的 `consumer_name + event_id` 去重，并以数据库约束守住同一 consumer 的至多一个业务副作用。

### Consumer 本地事务

一次成功消费必须在同一个本地事务中原子完成：

1. 领取或创建 Inbox 处理记录；
2. 执行该 consumer 所属模块的业务副作用；
3. 记录处理产生的后继 publication；
4. 将 Inbox 标记为完成并保存安全的结果摘要。

任一步失败都回滚该事务。重复投递必须返回既有结果或无害跳过，不得重复业务副作用；`first_received_at`、`last_attempt_at`、`processed_at` 和 `updated_at` 等处理时间不得因重放而倒退。同一 event ID 若 tenant、事件类型或 payload hash 不同，是 binding conflict，不是正常重复。

binding conflict 永远不得重新执行业务副作用。若同一 `consumer_name + event_id` 已存在 `PROCESSED` 或其他带可信结果证据的终态，必须保留原 Inbox 行的 binding、状态、结果引用/hash 和时间证据，并在独立的 anomaly、audit 或 failure observation 中追加冲突事实；不得把原 Inbox 行覆盖为 `FAILED_FINAL`。只有首次处理、没有任何既有成功或可信终态证据，且确认是不可恢复的 binding/契约错误时，才可把本次 Inbox 置为 `FAILED_FINAL`。

### 失败分类

- 已知永久性的契约版本不支持、payload/binding 冲突、业务身份不一致等不可恢复错误直接进入不可重试的 `FAILED_FINAL`，等待受控处置。
- 已知技术瞬态异常以及所有未显式分类的意外异常，默认进入 `FAILED_RETRYABLE`，按有上限的次数和退避预算重试；意外 `NullPointerException` 和其他编程错误属于这一类，不得伪装成契约错误。
- 可重试失败达到尝试上限后转为 `FAILED_FINAL` 并保持可观察；达到上限前不得因异常类型未分类而提前终止。
- 业务失败发布版本化业务事实；技术处理失败只保存在 publication/inbox 状态、日志和指标中，不把异常堆栈写入事件 payload。

### 与外部 Broker 的边界

本地 Consumer 成功只完成自己的 Inbox，不等于 Kafka 或其他外部 broker acknowledgement，也不得据此把外部发布标记为成功。未来接入 Kafka 时使用独立 external-outbox adapter 和独立发布状态；broker ack、重试、分区元数据及失败状态不得与本地 Inbox 完成状态混用。

Spring Modulith Event Publication Registry 当前不进入迁移范围。只有出现现有机制无法满足的明确能力、收益和可迁移证据时，才可通过新 ADR 评估替换。

## 理由

保留已经运行并经过事务与幂等测试的机制，能让 core profile 不依赖 broker，同时避免一次无业务收益的基础设施迁移。将本地消费与外部 acknowledgement 分开，也避免把模块内完成错误解释为外部消息已经交付。

## 后果与边界

- 团队需要继续维护自定义表、dispatcher、失败分类和数据库测试，而不能直接依赖框架注册表的运维能力。
- 后续必须补齐 retention、reconciliation、受权限和审计保护的 manual replay，以及 backlog、失败、处理延迟和 duplicate 指标。
- 手动重放不得修改原事件；需要纠正业务内容时发布新版本事件或补偿事实。
