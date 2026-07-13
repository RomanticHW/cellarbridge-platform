# ADR-010：共享表 tenant_id 判别式多租户

- 状态：Accepted
- 日期：2026-07-13

## 决策

所有业务表含 tenant_id，安全上下文绑定租户，Repository 显式过滤；缓存/事件/唯一键包含租户；双租户测试为门槛。

## 后果

本地和演示简单；隔离依赖严格工程纪律。可选 PostgreSQL RLS 作为后续 defense-in-depth，而非 P1 唯一机制。
