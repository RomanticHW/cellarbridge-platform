# ADR-019：Inventory Reservation Foundation 与三栈交付边界

- 状态：Accepted
- 日期：2026-07-16
- 关联：ADR-008、ADR-014、ADR-015、ADR-018

## 背景

Task 07C 已把完整、非空且可验证的 Supply Decision 传播到 Trade Order，订单仍诚实停留在 `PENDING_RESERVATION`。Task 08 需要同时交付持久化聚合、确定性原子分配、事件协作、订单结果、操作 API 与 UI；完整纵向切片预计超过单个 PR 的 60 文件和 2,200 churn 审阅门禁。

库存正确性的基础不能由临时 API、Mock 数据库或未来事务编排补齐。表结构、精确数量、幂等键、append-only 事实、状态约束、Repository round-trip 和 PostgreSQL 原子条件更新必须先形成一个可独立验证、但不对外宣称可用的 Foundation。

## 决策

### 1. 三栈顺序拓扑

Task 08 按事务和领域边界顺序交付：

1. **Stack A — Reservation Foundation**：Inventory-owned V15、领域模型、Request Hash、Repository、Lot 数量原语和真实 PostgreSQL 证明；
2. **Stack B — Reservation Execution**：事件输入分类、外层 delivery 事务、嵌套 allocation savepoint、确定性分配、错误/重试分类及 outcome events；
3. **Stack C — Outcome and Operations**：Trade Order outcome、API、React、release/consume 用例及完整 E2E。

每栈独立满足不超过 60 个 changed files 和 2,200 total churn。后续栈只能基于前一栈已审阅的分支顺序开始，不并行创建替代实现。

### 2. Stack A 所有权与不可用边界

Inventory 模块独占以下概念和表：

- `Reservation`；
- `ReservationAttempt`；
- `Allocation`；
- `InventoryMovement`；
- `ShortageSnapshot`；
- Lot 原子 reserve、release、consume 数量原语。

Stack A 不消费 `TradeOrderCreatedV1`，不读取 `trade_order`、`quotation`、`trade_planning` 或 Catalog 内部表，不发布 Reservation outcome event，不迁移 Trade Order 状态，也不新增 REST/OpenAPI/React 表面。Foundation 合并前后都不能被描述为“库存预占可用”；只有三个栈全部完成后才可改变能力状态。

### 3. V15 单一 Schema 所有权

首个新增 migration 固定为 `V15__inventory_reservation_foundation.sql`，owner 为 `inventory`。V15：

- 只创建或修改 `inventory.*`；
- 不修改、复制、重命名 V2～V14；
- 不建立跨 Schema FK，不设置 `search_path`，不使用 procedural/dynamic SQL；
- 所有逻辑跨模块引用只保存 UUID 和必要不可变快照；
- 与 migration ownership manifest 同步记录文件名、owner 和 SHA-256。

V15 是纯 forward migration：fresh V2→V15 和历史 V14→V15 都必须通过。新增表无旧数据 backfill，不重解释现有 Lot 数量；既有 Lot 表仅允许增加支持相同 Inventory 不变量的约束或索引。

### 4. Reservation 聚合与状态

Reservation 保存 `tenantId`、`reservationId`、`orderId`、`requestHash`、`supplyDecisionHash`、route、状态、失败摘要、版本和审计时间。公开状态机保持：

```text
PENDING -> CONFIRMED | FAILED
CONFIRMED -> RELEASED | CONSUMED
```

领域构造和 Repository hydration 都验证合法状态、精确数量、订单行唯一性、tenant 一致性和结果一致性。Stack A 只建立合法转换能力；没有应用服务调用这些转换来执行物理预占、释放或消费。

### 5. ReservationAttempt 为 append-only

Attempt 以 `(tenant_id, reservation_id, attempt_number)` 唯一，attempt number 严格为正。它保存 request hash、trigger、开始/结束时间、outcome、failure code、correlation/causation。已写 Attempt 不更新、不删除；Repository 只提供 append 和按 attempt number 稳定读取。

### 6. Allocation、Movement 与 Shortage

- Allocation 冻结 order line、source quotation line、SKU、单位、Supply Type、allocation mode、Pool、Lot、精确分配量、候选优先级证据和 remaining reserved；同一 Reservation/line/Lot 唯一。
- Movement 类型固定为 `RESERVE | RELEASE | CONSUME`，包含稳定 business identity；记录 append-only，不允许历史覆盖或删除。
- ShortageSnapshot 冻结 line、SKU、单位、requested、available、shortage、failure code 及 Pool/Type 边界；同一 Reservation/line 唯一。

所有数量使用 Java `BigDecimal` 与 PostgreSQL `numeric(19,6)`。核心数量不得使用 JSONB、`float` 或 `double`。Allocation 单位和 Supply Type 必须与对应 Reservation line snapshot 完全相同；不允许 CASE/BOTTLE 换算、跨 Supply Type 或跨路线补足。

### 7. Request Hash V1

Request Hash 至少覆盖：tenant、order、route、Supply Decision Hash，以及按稳定顺序排列的 line ID、source quotation line ID、SKU、quantity、unit、allocation mode、Pool 和 Supply Type。

Canonical 编码采用 UTF-8 的长度前缀字段帧：每个字段写入 ASCII 十进制字节长度、冒号、原值和换行；null 使用独立 `-1:` 帧，不能与空字符串混淆。UUID 使用小写 canonical form；数量先验证 `> 0`、精度不超过 19、scale 不超过 6，再固定为 scale 6 的 plain string；line 按 `skuId, unit, orderLineId` 排序。最终为 SHA-256 小写 64 位十六进制。

禁止依赖 JSON property 顺序、Java object serialization、默认 locale、Set/Map iteration 或科学计数法。固定向量、line permutation 和任一实质字段变化必须分别证明稳定、相同和不同结果。

### 8. 数据库幂等与唯一性

数据库最终守卫至少包括：

- Reservation `(tenant_id, order_id)` 唯一，并保存唯一 request hash；
- Attempt `(tenant_id, reservation_id, attempt_number)` 唯一；
- Allocation `(tenant_id, reservation_id, order_line_id, lot_id)` 唯一；
- Movement 以稳定业务 identity 唯一；
- Shortage `(tenant_id, reservation_id, order_line_id)` 唯一；
- 所有子表 FK 只指向同 tenant 的 `inventory` owner 表。

同 tenant/order/hash 重放由 Repository 读回原聚合；同 tenant/order 不同 hash 必须在任何新事实前暴露稳定 conflict。Stack A 不把该能力包装成事件消费结果。

### 9. Repository 与 fail-closed hydration

Inventory Repository 负责：

- create 和按 tenant/order、tenant/reservation、request hash 查询；
- expected-version 状态更新；
- append Attempt、Allocation、Movement、Shortage；
- 完整稳定顺序 round-trip；
- Lot 原子 reserve/release/consume。

每条查询必须先绑定 tenant。Hydration 对未知状态/类型、非法精度、重复 line/事实、Attempt 缺口、Allocation/Movement 不一致和数量守恒破坏 fail closed，不返回被修补或部分可信的聚合。

### 10. Lot 原子条件 SQL

Stack A 提供可被后续事务编排复用的单 Lot 原语，不提供跨 Lot 分配算法：

```sql
-- reserve
UPDATE inventory.inventory_lot
   SET reserved_quantity = reserved_quantity + :quantity,
       updated_at = :now,
       updated_by = :actor_id,
       version = version + 1
 WHERE tenant_id = :tenant_id
   AND id = :lot_id
   AND quantity_unit = :unit
   AND status = 'AVAILABLE'
   AND on_hand_quantity - reserved_quantity >= :quantity;
```

Release 额外要求 `reserved_quantity >= :quantity`；consume 同时要求 `reserved_quantity >= :quantity` 与 `on_hand_quantity >= :quantity`，并原子减少 reserved 和 on-hand。affected rows 必须恰好为 1 才算成功。禁止先 SELECT 总量再无条件 UPDATE，也不以 Redis、JVM/进程锁或 distributed lock 作为正确性前提。

### 11. Append-only 与事务能力

Stack A 的 Repository 方法不暗含跨事实自动提交；调用者可在一个事务中创建聚合和所有子事实，异常时由 PostgreSQL 整体回滚。Foundation 集成测试必须直接证明 partial write rollback。Stack B 才拥有外层 event delivery 和嵌套/savepoint allocation 编排，Stack A 不模拟或预先实现该事务层。

### 12. PostgreSQL 证明

Stack A 最低证明包括：

- fresh V2→V15 与 V14→V15；
- V2～V14 SHA-256 未变，V15 仅触及 Inventory，无跨 Schema FK；
- aggregate/repository 完整 round-trip、tenant isolation、expected-version 和 tamper rejection；
- 同请求幂等基础和不同 request hash conflict；
- partial transaction rollback；
- reserve/release/consume 正常与越量失败；
- 两个真实 PostgreSQL 事务竞争同一 Lot 时 `reserved_quantity <= on_hand_quantity`；
- 并发由 barrier/latch 或数据库协调点控制，不使用任意 sleep 判定正确性。

### 13. Stack A 实现拓扑与规模预算

预计 Stack A 修改 32～44 个文件、1,750～2,050 churn：

| 顺序 | 分组 | 预计文件 | 预计 churn | 依赖 |
|---|---|---:|---:|---|
| 1 | ADR、状态与设计证据 | 3～5 | 180～280 | 无 |
| 2 | Reservation/Attempt/Allocation/Movement/Shortage/Hash 领域 | 10～14 | 450～600 | ADR |
| 3 | V15、ownership manifest、Repository | 7～10 | 500～650 | 领域 |
| 4 | Lot 原子数量原语 | 2～4 | 120～200 | V15/Repository |
| 5 | migration/domain/repository/concurrency tests | 10～14 | 500～650 | 全部 Foundation 代码 |

每次逻辑提交后重新计算相对 `main` 的 changed files、additions、deletions 和 total churn。超过 60/2,200 时立即停止；不得通过删除真实 PostgreSQL 测试、压缩代码或弱化约束规避门禁。

## 后果

- Stack A 可以独立证明数据库结构、持久化语义和单 Lot 原子数量守卫，但不会产生任何可观察的订单预占结果。
- Stack B 必须复用本 ADR 的 aggregate、hash、Repository 和 Lot 原语，不能另建并行写路径。
- Stack C 必须复用已确认的 Allocation/Movement 守恒事实实现 release/consume 与操作表面。
- 只有三个栈全部通过各自验证后，公开实现状态才可从 Designed 改为 Available。
