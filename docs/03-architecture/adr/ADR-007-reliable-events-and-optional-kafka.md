# ADR-007：可靠模块事件，Kafka 为 Full Profile 外部通道

- 状态：Accepted
- 日期：2026-07-13

## 决策

跨模块使用持久化可靠事件发布；对外事件通过 outbox adapter 发送 Kafka。所有消费者 Inbox 幂等，语义为至少一次。

## 后果

core profile 不依赖 Kafka即可正确运行；full profile 展示 broker、积压和重放。系统不宣称 exactly-once。
