# ADR-004：REST + OpenAPI 3.1 作为浏览器 API 契约

- 状态：Accepted
- 日期：2026-07-13

## 决策

React 与后端通过 JSON REST 协作，OpenAPI 3.1 为审阅和类型生成契约；错误采用 RFC 9457 风格 Problem Details。

## 拒绝方案

GraphQL 对当前明确用例增加授权/缓存复杂度；gRPC 不适合作为浏览器主边界。
