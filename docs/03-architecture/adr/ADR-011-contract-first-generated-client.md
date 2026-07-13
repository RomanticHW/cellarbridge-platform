# ADR-011：审阅优先契约与生成前端客户端

- 状态：Accepted
- 日期：2026-07-13

## 决策

OpenAPI/AsyncAPI/JSON Schema 在实现前建立，并与代码同步。前端 API 类型由 OpenAPI 生成；生成差异在 CI 检查。

## 后果

减少前后端漂移、方便审阅；要求契约更新与实现同 PR，禁止手改 generated 文件。
