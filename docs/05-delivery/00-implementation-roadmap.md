# 实施路线图

## 1. 总体方法

实施采用**文档先行、纵向业务切片、每步可运行、每步可审阅**。任何实现工作都不得重新定义产品边界或静默改变架构。

阶段顺序：

```text
Design Baseline
→ Repository Foundation
→ Identity/Tenancy
→ Partner + Catalog
→ Quotation + Trade Planning
→ Customer Acceptance + Order
→ Architecture Integrity + Inventory Readiness Gate
→ Inventory Reservation
→ Fulfillment + Exception
→ Settlement + Reporting
→ Quality Hardening
→ Public Release
→ Authenticated Read-only MCP
```

## 2. 任务包

| Task | 名称 | 主交付 | 关键证据 |
|---:|---|---|---|
| 00 | 创建公开/私有仓库 | 文档、治理、tag | 链接/契约检查、无业务代码 |
| 01 | 可执行工程骨架 | Java/React/Docker/CI | core profile 启动、架构 verify |
| 02 | 身份、租户和权限 | Keycloak、JWT、RBAC/ABAC | 双租户与越权测试 |
| 03 | 客户准入 | 草稿、审核、激活 | 状态机、双人原则、React 流程 |
| 04 | 商品和供给 | SKU、搜索、供给摘要 | PG 搜索、性能计划 |
| 05 | 报价和路径 | 编辑、定价、评估、审批 | 确定性、解释、策略版本 |
| 06 | 客户接受 | 客户安全视图、幂等接受 | 字段安全、并发重复测试 |
| 07 | 订单转换 | 唯一订单、可靠事件 | quote unique + crash recovery |
| 07A | 架构完整性与库存准备门禁 | 领域/事件/数据语义纠偏；分为 core 与 inventory-readiness 两个 PR | 契约、hash、fitness 与准备证据；两阶段均合并后才放行 08 |
| 08 | 库存预占 | 原子全量预占 | 高并发无超卖 |
| 09 | 履约编排 | 模板、步骤、里程碑 | 依赖、SLA、公开时间线 |
| 10 | 异常中心 | 去重、分派、恢复 | 失败/重试/恢复证据 |
| 11 | 应收 | 付款、部分付款、冲正 | 金额与幂等测试 |
| 12 | 报表/审计 | 时间线、驾驶舱 | 重建、投影延迟、字段安全 |
| 13 | 可观测/安全强化 | OTel、指标、scan | trace、SBOM、tenant matrix |
| 14 | 性能/故障验证 | benchmark、chaos profile | 报告、可重现脚本 |
| 15 | 公开 v1.0 | README、演示、Release | 一键启动、Playwright、审阅路径 |
| 16 | 认证只读 MCP 与智能客户端工作流 | 同进程 `/mcp`、6 tools、3 resources、3 prompts | OIDC、权限/租户/字段边界、smoke、官方 conformance |

每个任务的可执行 Prompt 位于私有控制仓库，公共仓库只保存可长期维护的设计与实现证据。

Task 08 已为 **Available**：A1/A2/A2C、B1/B2、C1/C2 已按顺序完成原子预占、订单结果、幂等 release/consume、内部 API、Order workbench 与真实后端 E2E。

Task 09 已为 **Available**：V18 路线模板、不可变计划快照、依赖动作、SLA、模拟适配器、Trade Order 联动、Fulfillment 工作台、客户安全里程碑和契约已完成合并门禁。

Task 10 已为 **Available**：去重异常、分派与状态流转、源状态验证恢复、失败 publication 掩码视图与受控重放、React 工作台和自动验证已通过合并门禁。

Task 16 已为 **Available**：当前 `main` 通过 STATELESS Streamable HTTP 暴露认证只读 MCP，直接复用既有应用服务与安全边界；已发布标签 `v1.0.0` 早于该任务，不回写发布资产。

## 3. 纵向切片定义

一个完成切片至少包括：

- 需求/验收 ID；
- 聚合或策略；
- 应用用例；
- migration；
- OpenAPI/event 更新；
- REST/event adapter；
- React 页面/状态；
- 单元、集成、权限和 E2E；
- 审计/指标；
- README/状态更新；
- coherent commits。

不接受仅创建空 Entity/Repository/Controller 的“模块完成”。

## 4. 合并门槛

每个 PR：

1. 任务范围与设计引用；
2. CI 全绿；
3. migrations 可在空库和上一 snapshot 执行；
4. architecture tests；
5. security/tenant tests（相关时）；
6. contracts generated diff；
7. UI screenshot/Playwright trace（相关时）；
8. 实现状态真实更新；
9. 无秘密和私人资料；
10. reviewable commits。

## 5. 分支与发布

- `main` 始终可构建；
- 分支 `task/NN-short-name`；
- 设计基线 tag `design-baseline-v1.0`；
- 里程碑 tags `v0.1.0`…；
- 最终 `v1.0.0`；
- 不在公开仓库保存长期 dev 分支或内部 Prompt。

## 6. 风险控制

| 风险 | 控制 |
|---|---|
| 范围膨胀 | 每任务 permitted paths/out-of-scope |
| 实现阶段设计漂移 | AGENTS + design sources + stop conditions |
| 中间件先行 | core profile，按需求引入 |
| 文档与代码不一致 | 同 PR、fitness functions |
| 前端拖尾 | 每切片含 UI，不最后补 |
| 无法演示 | 每里程碑可运行、种子和脚本 |
| 公开历史泄露 | 从第一天双仓库，不事后清理为主 |
