# ADR-016：路线供给决策就绪度分为四层

- 状态：Accepted；日期：2026-07-15

**背景与关系：** 路线绑定的供给决策跨越库存可用性、路线规划、报价冻结与订单传播。若把纯决策基础、业务集成和库存写入合并为一个“可用”状态，会掩盖尚未完成的边界并提前承诺预留能力。

## 决策

能力按以下顺序交付，后层不得反向替代前层证据：

1. **Foundation（completed）：** 已定义带显式 `decisionAt` 的库存查询语义、无副作用的纯供给决策、规范化哈希及其确定性与边界测试。该层不集成 Route Evaluation，不冻结报价，不传播订单，也不写库存。
2. **Planning（completed）：** 已将 Foundation 接入 `ROUTE-2026-03` Route Evaluation，以一个微秒对齐时间和 canonical input schema 3 形成并持久化 selected-route Supply Decision；Quotation 尚未冻结该决定，因此产品能力仍不得标记为 `Available`。
3. **Quotation（in progress）：** 按 [ADR-017](ADR-017-quotation-owned-route-bound-supply-decision-freeze.md) 在报价边界冻结经确认的业务供给决策，使后续流程引用同一不可变决定；冻结不等于库存预留。
4. **Propagation：** 将冻结结果传播到 Order，保持决策身份、版本与哈希可追溯；传播不产生库存写入。

只有 Task 08 对库存执行写入并建立预留语义。在 Task 08 完成并通过对应验证前，reservation 不得标记为 `Available`；Foundation、Planning、Quotation 或 Propagation 的完成均不能替代该门禁。

**后果：** 就绪度必须逐层报告，评审证据分别对应纯决策、路线集成、报价冻结、订单传播和库存写入。任何跨层实现都须保留显式 `decisionAt`、确定性哈希与不可变业务决定，并且不得用下游状态倒推上游能力已经可用。
