# ADR-002：按业务模块组织代码并封装 internal

- 状态：Accepted
- 日期：2026-07-13

## 决策

Java 根包按限界上下文组织；公开模块 API 在根/named interface，所有实现置于 `internal`。使用 Spring Modulith + ArchUnit 验证。

## 后果

跨模块依赖显式，重构影响可见；需要少量契约 DTO，禁止共享 JPA entity。
