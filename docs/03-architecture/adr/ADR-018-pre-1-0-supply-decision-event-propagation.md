# ADR-018：pre-1.0 供给决策事件传播

- 状态：Accepted；日期：2026-07-15

**背景与关系：** [ADR-017](ADR-017-quotation-owned-frozen-supply-decisions.md) 让 Quotation Revision 独立拥有经过验证的路线绑定 Supply Decision。Trade Order 与后续 Task 08 不能读取 Quotation、Trade Planning 或 Inventory 的内部表来补全该决定，因此事件必须携带自足证据。当前仓库仍为 `0.1.0-SNAPSHOT`，没有 GitHub Release、部署或已确认的仓库外消费者；Quotation producer、Trade Order consumer、Order producer 与 Quotation link consumer 将在同一堆叠交付中同步升级。这个事实只支持受控的 pre-1.0 仓库内变更，不代表新增可选字段对未知严格消费者天然兼容。

## 决策

1. `QuotationAcceptedV1` 与 `TradeOrderCreatedV1` 保持现有事件 type、channel、message name 和 V1 版本号，只增加可选的 Supply Decision 证据。
2. 当前事件在 `payload.supplyDecision` 携带根元数据；每个 `payload.lines` 元素携带 `allocationMode`，并继续用行级 `supplyPoolId` 和 `supplyType` 表达实际决定。
3. Supply Decision 根对象存在时内部字段必须完整，每条行证据也必须完整；根缺失时所有行的 `allocationMode` 都必须缺失。显式 null、混合 Current/Legacy 或不完整 Current 事件无效。
4. Legacy V1 事件缺少 Supply Decision 与 allocation mode 时仍可读取，但只能创建 `LEGACY_UNVERIFIED` Trade Order。消费者不得读取上游内部表、根据历史 Pool/Type 推断模式或静默升级为 `FROZEN`。
5. 新 Quotation producer 只能从经过防御性复核的 `FROZEN` Revision 发布事件，并且必须始终发送完整根证据和全部行的 allocation mode。
6. Trade Order consumer 必须从事件根与行重新构造 `SupplyDecisionSnapshot`，重算 `SupplyDecisionHashV1`，并验证路线、策略、Schema、行身份、SKU、数量、单位、模式、Pool 与 Supply Type。事件自报 hash 不是可信输入。
7. Trade Order 在自身 Schema 独立拥有 Supply Decision 副本，以 `FROZEN` 或 `LEGACY_UNVERIFIED` 明确分类。根字段、JSON 快照与订单行必须交叉一致，二者不得混合或相互静默升级。
8. 新 Trade Order producer 对 `FROZEN` Order 必须始终发布完整 Supply Decision；对 Legacy Order 必须省略根对象和 allocation mode。
9. `QuotationSnapshotHashV1` 继续只表达既有商业快照，字段集合和 canonical 顺序保持不变，不包含 `allocationMode` 或 `supplyDecision`。Supply Decision 继续使用独立的 `SupplyDecisionHashV1`。
10. Quotation 的 Order Link consumer 同时接受 Current 与 Legacy `TradeOrderCreatedV1`，但只负责商业 snapshot、Revision、Acceptance 和 Order 身份回链，不复制或重新验证 Supply Decision。
11. Buyer 与 Customer DTO 不暴露 allocation mode、Pool、Supply Type、策略、Evaluation ID、输入 hash 或决定 hash。内部 Order API 可以展示经验证的证据；Legacy 只展示受控告警。
12. Task 08 只把 `FROZEN` `TradeOrderCreatedV1` 视为可尝试库存预占的输入。Legacy 输入保持不可验证，并进入 Task 08 单独定义的失败协议。
13. 本层不写 Inventory，不创建 Reservation、Allocation、Movement、Attempt 或 Shortage，不执行 release/consume，也不改变 Trade Order 的 `PENDING_RESERVATION` 状态。
14. V14 只修改 `trade_order` Schema，不建立跨 Schema 外键；V2-V13 保持不可变。
15. OpenAPI 升级至 1.7.0，AsyncAPI 设计版本升级至 1.1.0-design；只有两个目标 V1 事件发生 additive shape 变化。
16. Quotation 与 Propagation PR 都保持未合并。最终顺序为先以 Merge Commit 合并 Quotation，再以 Merge Commit 合并 Propagation，并在此之前完成单独 Owner review。

## 后果

Current 事件和 Order 可以端到端证明同一 Supply Decision 身份；Legacy 数据继续可读但不会获得伪造的冻结证据。事件消费者增加严格 presence 分类与 hash 重算，数据库增加独立的根/JSON/行一致性约束。冻结与传播仍只是分配约束，不是库存承诺；Task 08 保持 blocked，直到两个堆叠 PR 经审查、顺序合并、final-main 验证且其重写 Prompt 被单独授权。
