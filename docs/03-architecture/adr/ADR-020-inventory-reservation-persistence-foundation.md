# ADR-020：Inventory Reservation 持久化与原子库存基础

- 状态：Accepted
- 日期：2026-07-16
- 关联：ADR-008、ADR-012、ADR-014、ADR-015、ADR-019

## 背景

ADR-019 将 Task 08 拆为顺序交付的 A1、A2、B、C。A1 已冻结 Reservation 领域对象、精确数量与 Request Hash 语义，但没有数据库表、Repository 或库存数量写入能力。A2 需要为后续执行层建立可独立审阅的 PostgreSQL correctness 边界，同时不能提前实现订单预占工作流。

库存正确性不能依赖应用进程内互斥、分布式锁或“先读再写”。持久化还必须在读取时拒绝残缺或被篡改的聚合，避免把数据库损坏解释成合法业务状态。

## 决策

### 1. A2 边界

A2 只交付：

- Inventory-owned V15 migration；
- Reservation、Attempt、Allocation、Movement、Shortage 的 SQL-first persistence；
- tenant-scoped Repository 和乐观版本更新；
- Lot reserve、release、consume 的单语句条件更新原语；
- migration、round-trip、tamper、idempotency、tenant isolation 和真实 PostgreSQL concurrency tests。

A2 不消费 `TradeOrderCreatedV1`，不编排订单预占，不发布 Reservation outcome event，不修改 Trade Order，不新增 OpenAPI、REST 或 React 表面。上述能力分别留给 Stack B/C。

### 2. V15 所有权

V15 只修改 `inventory` schema，并在 migration ownership 清单中归属 `inventory`。V15 不修改 V2～V14，不创建跨 schema foreign key，不设置 `search_path`，不包含 demo UUID，也不引入隐式单位换算。

Reservation 子事实只能引用同属 Inventory 的父事实或 Lot。跨模块 identity 作为不可变 UUID/value 保存，不建立跨 schema foreign key。

### 3. 持久化模型

`inventory.reservation` 保存聚合根 identity、tenant/order/request hash、Supply Decision Hash、route、状态、失败摘要、请求行快照、乐观版本与时间。数据库唯一键同时约束 tenant/order 和 tenant/request hash；相同 tenant/order 的不同 request hash 必须产生稳定冲突。

请求行快照使用带 schema version 的受约束 JSONB，因为失败或 Pending Reservation 可能没有 Allocation，不能从 Allocation 反推原始请求。数据库只接受非空数组；Repository 必须按 A1 领域构造器完整 hydrate，任何未知字段值、缺失字段、重复行、非法数量或 Current/Legacy 混合证据均 fail closed。

`inventory.reservation_attempt`、`inventory.allocation`、`inventory.inventory_movement` 和 `inventory.shortage_snapshot` 保存不可变子事实。写端只提供 append 方法；唯一键、check constraint 与同 schema composite foreign key 加强 identity、数量守恒和 tenant 一致性。A2 不提供更新或删除历史事实的 Repository API。

### 4. 精确数量

所有持久化数量列使用 `numeric(19,6)`，Java 绑定和读取使用 `BigDecimal`。写入边界要求 scale 6 且无需舍入，读取结果必须重新经过 A1 领域不变量。

禁止使用 `float`、`double`、科学计数法持久化或 CASE/BOTTLE 换算。Allocation 继续满足：

```text
allocated = released + consumed + remaining_reserved
```

Lot 继续满足：

```text
0 <= reserved_quantity <= on_hand_quantity
```

### 5. Idempotency 与版本

Reservation create 以数据库唯一约束作为并发仲裁：

- 相同 tenant、order、request hash 返回已存在结果，不重复创建；
- 相同 tenant、order、不同 request hash 返回 `RESERVATION_REQUEST_CONFLICT`；
- 不以预读结果作为 correctness 前提。

状态写入使用 `tenant_id + reservation_id + expected_version` 条件，并在成功时只递增一个版本。affected rows 为零表示不存在、tenant 不匹配或版本冲突，调用方不能将其伪装成成功。

### 6. 聚合读取 fail closed

Repository 读取先 hydrate 聚合根和全部子事实，再验证数据库事实与 A1 状态：

- final outcome 必须有对应 Attempt；
-成功态必须有覆盖全部请求行的 Allocation 与 RESERVE Movement；
- Allocation 数量和 Movement 汇总必须与冻结数量一致；
- FAILED 不得携带 Allocation 或库存 Movement；
- PENDING 不得携带 outcome 子事实。

任一缺行、tenant 错配、非法 enum、非法数量、版本异常或数量不守恒均抛出 persistence corruption error，不返回部分聚合。

### 7. Lot 原子条件更新

reserve、release、consume 分别使用单条 PostgreSQL `UPDATE ... WHERE ... RETURNING`。条件同时覆盖 tenant、Lot identity、unit、合法状态、正数量和当前余额：

```text
reserve: available = on_hand - reserved >= quantity
release: reserved >= quantity
consume: reserved >= quantity and on_hand >= quantity
```

reserve 原子增加 `reserved_quantity`；release 原子减少 `reserved_quantity`；consume 原子同时减少 `reserved_quantity` 与 `on_hand_quantity`。affected rows 为零即稳定失败，不再通过预读推断原因。

这些原语不依赖 Redis、JVM lock、`synchronized`、外部分布式锁或可重复读快照来防止超卖。数据库条件更新是唯一 correctness boundary。

### 8. 真实 PostgreSQL 证明

A2 使用仓库锁定的 PostgreSQL 18.x Testcontainers，分别证明：

- 空库从 V2 升至 V15；
- 既有 V14 schema 升至 V15；
- migration history 和 ownership hash 一致；
- Repository round-trip、tenant isolation、乐观版本、tamper fail-closed 和并发 idempotency；
- 同 Lot 多请求竞争、并发 release/consume，以及任意结果下 `reserved <= on_hand`。

并发测试使用受控 barrier/latch 同时放行，不使用 sleep 判断正确性，也不以 Mock、H2 或内存 Repository 替代 PostgreSQL。

### 9. 顺序交付

A2 的公开分支基于 A1 的精确 Head。Stack B 只有在 A2 Owner review 完成并批准后才能创建；Stack C 同理。A2 Review Ready 只表示持久化基础可审阅，不表示 Inventory Reservation 已可用。

## 后果

- Inventory 获得受约束的 Reservation persistence 和可复用的原子 Lot 数量原语，但没有业务执行入口。
- 后续 Stack B 必须在事务/savepoint 编排中复用这些原语，不能增加另一套库存更新算法。
- 后续 Stack C 必须在已确认的 Reservation 语义上实现 release/consume 用例，不能直接绕过 Repository。
- Task 08 在 A、B、C 全部完成、E2E 与安全门禁通过前持续标记为 `Designed / In progress`，不得标记 `Available`。
