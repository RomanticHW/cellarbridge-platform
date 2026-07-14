# 技术评审评分卡

## 1. 使用方式

本评分卡不是自我授予的“企业级”标签，而是一条可验证的评审路径。招聘方或代码评审者可以按 0~3 分独立判断每项：

- `0`：没有证据或行为不正确；
- `1`：存在实现，但边界、测试或解释不足；
- `2`：设计与实现一致，有代表性自动测试；
- `3`：除正确实现外，还有故障、并发、安全或运维证据，且可复现。

Design Baseline 阶段只提供设计证据，不应预填实现分数。

## 2. 评审维度

| 维度 | 评审问题 | 首选入口 | 3 分证据示例 |
|---|---|---|---|
| 产品判断 | 是否选择了真实、连贯且适合技术展示的业务闭环，而非普通商城？ | README、scenario selection、product vision | 真实/推断/Demo 边界清楚；功能范围克制；演示可闭环 |
| 领域建模 | 聚合、值对象、状态机和业务不变量是否明确？ | bounded contexts、aggregates、state machines | 非法转换被领域方法拒绝；快照和金额语义可测试 |
| 模块架构 | 模块所有权和依赖方向是否能被构建自动验证？ | architecture overview、module rules | Spring Modulith/ArchUnit 能阻止跨 internal、循环和错误分层 |
| 数据与事务 | 数据归属、migration、事务和历史语义是否可靠？ | data ownership、database design | module-owned schema、immutable migrations、unique/check constraints、rollback tests |
| 幂等与事件 | 重复请求、重复事件和崩溃窗口是否被真实处理？ | idempotency、event design | quote→order at-least-once delivery + at-most-one business effect、Inbox/outbox、crash-point tests |
| 并发正确性 | 是否能证明库存不会超卖，而非只使用一个锁注解？ | ADR-008、inventory task/evidence | atomic conditional SQL、deterministic order、high-contention Testcontainers invariants |
| 安全与租户 | 是否覆盖认证、授权、对象归属、字段和租户隔离？ | security/tenancy、permission matrix | role × tenant × ownership × state tests，客户 DTO allow-list，日志脱敏 |
| API 与契约 | HTTP/event 契约是否稳定、一致、可生成和可验证？ | OpenAPI、AsyncAPI、contract docs | Problem Details、idempotency/ETag、schema examples、compatibility decisions |
| React 交付 | 前端是否真正表达业务状态、权限、并发和异常恢复？ | page specs、frontend architecture | loading/empty/error/403/409、generated client、关键 Playwright 纵向流程 |
| 测试策略 | 测试是否针对风险，而非只追求覆盖率？ | testing strategy、acceptance criteria | domain properties、DB integration、architecture、security、concurrency、E2E 分层 |
| 可观测与恢复 | 能否沿请求、事件、异常和恢复定位问题？ | observability、failure model | correlation/causation trace、publication backlog、controlled replay、recovery evidence |
| 性能证据 | 是否提供带环境和正确性断言的可重复测试？ | performance design/evidence | dataset/seed、p50/p95/p99、query plans、invariant checks、before/after |
| 工程交付 | 新评审者能否克隆、启动、理解和复现？ | README、reviewer guide、release plan | one-command demo、locked dependencies、CI、SBOM、release assets、clean history |

## 3. 建议的面试抽查

### 10 分钟

1. 解释为什么选择模块化单体；
2. 展示 route evaluation 的拒绝原因与评分；
3. 展示库存原子 SQL 和并发测试；
4. 展示 README 的完成状态与 review path。

### 30 分钟

1. 从报价接受追踪到唯一订单和可靠事件；
2. 检查一个跨租户越权测试；
3. 检查一个 React 409 冲突恢复；
4. 检查一个失败 publication/异常恢复；
5. 运行 core smoke 或核心 Testcontainers suite。

### 60 分钟

1. 从零启动演示；
2. 执行完整主旅程；
3. 触发一次库存不足或履约失败；
4. 检查 trace、审计时间线和 dashboard；
5. 运行并发/性能 smoke 并核对不变量。

## 4. 诚实披露

评审材料必须区分：

- 设计完成但尚未实现；
- 已实现但未完成风险验证；
- 已通过自动测试；
- 仅在本地特定环境测量的性能；
- 刻意不在 P1 实现的真实支付、海关法规、WMS/TMS 集成和微服务拆分。

可信度来自边界和证据，而不是形容词数量。
