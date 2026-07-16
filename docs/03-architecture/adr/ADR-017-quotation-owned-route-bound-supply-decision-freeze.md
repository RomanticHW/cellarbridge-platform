# ADR-017：报价拥有路线绑定供给决策冻结证据

- 状态：Accepted；日期：2026-07-15

**背景与关系：** `ROUTE-2026-03` 已在 Trade Planning 中为最终选中路线生成并保存 `SUPPLY-DECISION-2026-01`。Quotation 必须在同一业务事务中把这份证据复制到当前 Revision，才能让提交、签发和客户决定引用稳定的行级供给约束。Planning Evaluation 是来源证据，但不能成为报价读模型的运行时联表依赖；供给决定也不代表库存已经预留。

## 决策

1. Quotation Revision 独立拥有一份不可变 `SupplyDecisionSnapshot` 副本，并以 `UNDECIDED`、`FROZEN`、`LEGACY_REEVALUATION_REQUIRED` 表达证据状态。只有完整、验证通过的当前证据可以进入 `FROZEN`；历史 Revision 不补造证据。
2. 路线应用必须在一个事务内完成 Planning Evaluation、报价重定价、严格行映射和 Revision 保存。决定按 `quotationLineId` 与报价行一一匹配，同时复核 SKU、六位小数数量、单位、分配模式、Pool 与 Supply Type。
3. 决定的 `selectedRouteCode` 必须与 Revision 的最终选中路线一致。Issue 不重新运行空 `requestedRoute` 的推荐；它复用并验证原路线应用的 Planning Evaluation 身份、路线、策略、输入哈希和决定哈希。
4. Draft 创建与替换会重置决定。`ROUTE_ELIGIBLE_AUTO` 只冻结 Supply Type，不冻结 Pool 或 Lot；`FIXED_POOL` 必须保留明确 Pool，且不得自动回退。
5. Quotation 的持久化读取只使用自身 Schema 的根字段、JSON 快照和行字段进行交叉验证，不跨 Schema 建立外键或查询 Trade Planning 表。V13 只修改 `quotation` Schema。
6. 内部 API 可以展示审计摘要和行级决定；客户与 Buyer 边界继续隐藏内部证据。事件 V1、AsyncAPI、Trade Order 与 Inventory 均不在本层变更范围。

## 后果

提交、签发、客户接受和拒绝都必须通过 `FROZEN` 门禁。证据缺失或冲突会失败关闭，事务失败不得遗留 Planning Evaluation 或部分报价快照。冻结仅形成 Task 07C 后续传播层的稳定输入；库存 Reservation、Allocation、Movement、Attempt、Shortage、release/consume 仍只属于 Task 08。

