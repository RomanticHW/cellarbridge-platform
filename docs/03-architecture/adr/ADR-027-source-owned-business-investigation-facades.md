# ADR-027：源模块拥有的业务调查 Facade
- 状态：Accepted；日期：2026-07-28
- 关联：ADR-002、ADR-016、ADR-017、ADR-026
## 背景与决策
交易调查必须读取当前源事实；Audit/Reporting 的最终一致投影不能证明当前状态，也不能代替对象授权。Quotation、Trade Order、Inventory Reservation、Fulfillment、Exception Center、Settlement 因此各公开一个窄、只读、immutable investigation contract，实现留在所属模块。
每次调用必须从当前 `TenantContext` 重新取得 tenant、actor、permission、role、partner 与 warehouse assignment；业务参数不得携带这些安全属性。所属 schema 的 SQL 同时绑定 tenant 和 owner/partner/typed-source；猜测 UUID、错误关系、同 tenant 越权和跨 tenant 都不可枚举。返回固定 DTO 并按字段权限裁剪，不暴露 `internal`、aggregate、Repository、JDBC 或自由 Map。
Quotation 仅在自身授权后读取公开 Trade Planning evaluation；exact supply 还需 `inventory:read`、`inventory:read-exact` 和 Inventory assignment，Buyer 永不可见。Fulfillment 可用 Inventory 的窄 facade 验证 plan reservation assignment，但 Warehouse 还须有当前 active warehouse-owned task；依赖固定为 `Fulfillment -> Inventory`，禁止反向依赖。
## 架构与关系
| 调用 | 唯一用途 |
|---|---|
| Quotation -> Trade Planning | 已授权 quotation 的冻结路线证据 |
| Quotation -> Inventory | exact supply-pool assignment |
| Fulfillment -> Inventory | reservation warehouse assignment |
| Audit/Reporting -> 六个 facade（后续） | 有界 case 编排，不读取 projection 补交易事实 |
仅允许现有列证明 quotation/revision/order、reservation/order、fulfillment/order/reservation、exception typed source、receivable order/typed trigger；禁止用编号、时间、文本、通用 JSON 或模型推断关系。调用只依赖模块根包，Modulith/ArchUnit 证明无 `internal` 依赖、跨 schema SQL 或环。`platform` 只拥有 transport/schema/budget/cursor/error mapping，不拥有业务规则。
Direct source 使用 `projectionStatus=NOT_APPLICABLE`、最新可见源时间作为 `dataAsOf`、`freshness.mode=OBSERVATION_AGE`，projection watermark 为空。本阶段不注册 MCP 能力，不改 migration、写路径、事件或 discovery；后续 coordinator 需新任务。拒绝 projection 拼接、Provider/platform 重写授权、共享调查模块和持久化 case graph。回滚只删除 facade 及本增量。
