# ADR-003：PostgreSQL 按模块 Schema 所有权

- 状态：Accepted
- 日期：2026-07-13

## 决策

使用单 PostgreSQL 实例，每个业务模块独立 schema 和迁移；禁止跨模块 FK/直接表访问。跨边界保存 ID 与快照。

## 理由

兼顾本地运行、事务能力和边界可提取性。

## 后果

需要事件/查询 API 组合数据；不能依赖任意跨 schema join。
