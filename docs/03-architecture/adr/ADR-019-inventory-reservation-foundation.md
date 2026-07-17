# ADR-019：Inventory Reservation A1/A2 Foundation 边界

- 状态：Accepted
- 日期：2026-07-16
- 修订：2026-07-16，Stack A 拆分为 A1/A2
- 关联：ADR-008、ADR-014、ADR-015、ADR-018

## 背景

Task 07C 已把完整、非空且可验证的 Supply Decision 传播到 Trade Order，订单仍诚实停留在 `PENDING_RESERVATION`。完整 Task 08 同时需要领域、不变量、持久化、原子库存更新、事件协作、订单结果和操作表面，不能在一个可审阅变更中安全完成。

最初的 Reservation Foundation 又同时包含领域语义和 PostgreSQL correctness。实现评估确认，持久化、条件更新与真实并发证明本身构成独立审阅边界；若与领域层放在同一 Stack A，将超过固定的 2,200 churn 门禁。因此不提高门禁，而是把 Stack A 拆为顺序 A1/A2。

## 决策

### 1. 四层顺序拓扑

Task 08 按领域和事务边界顺序交付：

1. **A1 — Reservation Domain Foundation**：领域对象、不变量、Request Hash、focused unit tests；
2. **A2 — Persistence and Atomic Inventory Foundation**：Inventory-owned V15、Repository、Lot 原子数量原语、真实 PostgreSQL migration/round-trip/rollback/concurrency 证明；
3. **Stack B — Reservation Execution**：事件输入分类、外层 delivery 事务、嵌套 allocation savepoint、确定性分配、错误/重试分类及 outcome events；
4. **Stack C — Outcome and Operations**：Trade Order outcome、API、React、release/consume 用例及完整 E2E。

每层独立满足不超过 60 个 changed files 和 2,200 total churn。A2 只有在 A1 Owner review 完成并批准后，才可从 A1 merge result 创建；B/C 依次遵守相同规则。

### 2. A1 所有权与不可用边界

A1 只建立 Inventory 模块内以下领域语义：

- `Reservation` 与合法状态迁移；
- `ReservationAttempt` 及不可变 append-only History；
- `Allocation` 数量守恒和冻结证据；
- `InventoryMovement` 类型、正数量和稳定业务 identity；
- `ShortageSnapshot` 的 requested/available/shortage 等式；
- `ExactQuantity` 的 `numeric(19,6)` 等价规则；
- `ReservationRequestHashV1` 的确定性 canonical 编码。

A1 不包含 Repository、JDBC、migration SQL、V15、Inventory 表、Lot 更新或并发证明；不消费 `TradeOrderCreatedV1`，不发布 outcome event，不迁移 Trade Order 状态，也不新增 REST/OpenAPI/React 表面。A1 完成后，库存预占仍不可用。

### 3. Reservation 聚合与状态

Reservation 保存 `tenantId`、`reservationId`、`orderId`、`requestHash`、可空的 `supplyDecisionHash`、route、状态、失败摘要、版本、时间和订单行请求快照。公开状态机保持：

```text
PENDING -> CONFIRMED | FAILED
CONFIRMED -> RELEASED | CONSUMED
```

每次转换返回新的不可变 Reservation 并递增版本。`FAILED` 必须有稳定 failure code；其他状态禁止携带 failure code。合法 Legacy 只允许全部行都没有决策证据，并直接表达为 `SUPPLY_DECISION_MISSING` 的受控 FAILED；Current 与 Legacy 行证据不得混合。

订单行必须有唯一的 `orderLineId` 和 `sourceQuotationLineId`。`ROUTE_ELIGIBLE_AUTO` 不冻结 Pool；`FIXED_POOL` 必须携带 Pool；部分 null 决策证据一律拒绝。

### 4. ReservationAttempt 为领域级 append-only

Attempt 是不可变事实，保存 request hash、trigger、开始/结束时间、outcome、failure code 和 correlation/causation。attempt number 从 1 开始且严格连续。

`ReservationAttempt.History` 构造时验证同 tenant、同 Reservation 和连续编号；`append` 只接受下一个编号并返回新的不可变 History。旧 History 和其中 Attempt 不被修改，集合视图不可写。A2 才负责用数据库唯一约束和 append-only 写路径加强该语义。

### 5. Allocation、Movement 与 Shortage

- Allocation 冻结 order line、source quotation line、SKU、单位、Supply Type、allocation mode、Pool、Lot、分配量、released/consumed/remaining 和 Warehouse priority/version 证据。
- Allocation 始终满足 `allocated = released + consumed + remainingReserved`，所有数量精确且非负，allocated 必须大于零。
- Movement 类型固定为 `RESERVE | RELEASE | CONSUME`，数量必须大于零，且携带不可空稳定 business key。
- ShortageSnapshot 冻结 line、SKU、单位、requested、available、shortage、failure code 及 Pool/Type 边界，并满足 `shortage = max(requested - available, 0)`；失败 shortage 必须大于零。

A1 不执行任何数量写入，也不声称这些事实已被数据库持久化。

### 6. ExactQuantity

所有领域数量使用 `BigDecimal`，构造时规范为 scale 6，并要求不发生舍入。precision 不得超过 19；负数被拒绝，需要正数的字段同时拒绝零。规范值使用 plain decimal 表达，不使用 `float`、`double` 或科学计数法。

该规则只证明 Java 领域等价语义；PostgreSQL `numeric(19,6)` schema 和 round-trip 属于 A2。

### 7. Request Hash V1

Request Hash 覆盖 tenant、order、route、Supply Decision Hash，以及每行的 order/source line ID、SKU、quantity、unit、allocation mode、Pool 和 Supply Type。

Canonical 编码采用 UTF-8 长度前缀字段帧：字段写入 ASCII 十进制字节长度、冒号、原值和换行；null 使用独立 `-1:` 帧，不能与空字符串混淆。UUID 使用小写 canonical form；数量使用 scale-6 plain string；line 按 `skuId, unit, orderLineId` 排序。最终结果为 SHA-256 小写 64 位十六进制。

禁止依赖 JSON property 顺序、Java object serialization、默认 locale、Set/Map iteration 或科学计数法。A1 必须分别证明固定向量、line permutation 稳定、任一实质字段变化产生不同 hash，以及重复 order/source line 被拒绝。

### 8. A2 延后边界

A2 独立拥有 V15、migration ownership、Reservation persistence、idempotency uniqueness、Repository hydration、Lot 原子条件更新、transaction rollback 和真实 PostgreSQL concurrency proof。ADR-008、ADR-014、ADR-015 的数据库正确性要求保持不变，但不构成 A1 实现或验证声明。

A1 不得为 A2 创建临时内存 Repository、伪 JDBC adapter、Mock concurrency test 或公开测试 API。A2 必须在单独授权后实现真实 SQL-first 路径。

### 9. A1 验证和规模门禁

A1 最低验证为十个可独立定位的 focused unit 场景：Reservation 不变量、Attempt append-only、Allocation 守恒、Movement、Shortage、ExactQuantity、Request Hash 固定向量、permutation 稳定、material change 和 duplicate line rejection。

A1 同时运行 Java 21 backend compile、Spotless、文档校验和禁止项扫描。相对 `main` 的 changed files 不得超过 60、total churn 不得超过 2,200；不得通过合并不可定位场景、压缩代码或弱化断言规避门禁。

## 后果

- A1 只证明 Reservation 领域模型和 Request Hash；公开能力状态保持 `Designed / In progress`，绝不标记 `Available`。
- A2 必须复用 A1 的不可变模型、精确数量和 Hash，不能建立并行领域语义。
- Stack B/C 必须依次复用前层成果；没有任何层可提前创建或并行实现。
- 只有完整 Task 08 的持久化、执行、结果和操作链全部完成后，库存预占能力才可改为 `Available`。
