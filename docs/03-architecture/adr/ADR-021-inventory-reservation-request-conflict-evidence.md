# ADR-021：Inventory Reservation Request Conflict 不可变证据

- 状态：Accepted
- 日期：2026-07-16
- 关联：ADR-008、ADR-014、ADR-015、ADR-019、ADR-020

## 背景

Inventory 对每个 tenant/order 只保留一个 canonical Reservation。相同 request hash 的重复处理复用既有结果；不同 request hash 必须稳定返回 `RESERVATION_REQUEST_CONFLICT`，且不得把旧成功误报为本次成功。

canonical Reservation 的 request hash 和 Attempt 历史均不可变。若把冲突 hash 追加到既有 Attempt，会破坏聚合身份；若创建第二个 FAILED Reservation，会破坏 tenant/order 唯一性；若把既有 CONFIRMED Reservation 改成 FAILED，会篡改已经成立的业务事实。冲突因此需要独立、不可变且可幂等重放的 Inventory-owned 证据。

## 决策

### 1. A2C 边界

A2C 在 A2 merge result 之上新增：

- `ReservationRequestConflict` 不可变领域事实；
- Inventory-owned V16 表、约束与 migration ownership；
- tenant-scoped Repository；
- domain、migration、round-trip、idempotency、tenant isolation、tamper 和真实 PostgreSQL concurrent-insert tests。

A2C 不消费事件、不发布 Reservation outcome、不执行 allocation、不修改 Trade Order，也不新增 API、OpenAPI 或 React 表面。B1/B2/C 在 A2C Owner review 完成前保持 blocked。

### 2. 不可变冲突事实

`ReservationRequestConflict` 保存：

- conflict ID、tenant ID、order ID；
- canonical Reservation ID 与 existing request hash；
- conflicting request hash；
- source event ID、correlation ID、首次观察时间；
- 固定 failure code `RESERVATION_REQUEST_CONFLICT`。

existing 与 conflicting hash 必须都是小写 SHA-256，且不能相同。事实不保存原始 payload，不保存库存数量，也不提供 update/delete Repository API。

### 3. 数据库身份与租户边界

V16 只修改 `inventory` schema：

- `(tenant_id, order_id, conflicting_request_hash)` 唯一；
- `(tenant_id, source_event_id)` 唯一；
- 通过同 schema composite foreign key 把 tenant、canonical Reservation ID 与 existing request hash 绑定到 `inventory.reservation`；
- 通过 CHECK 固定 failure code、hash 格式、不等关系和时间字段。

不新增 cross-schema foreign key，不读取 Trade Order、Quotation、Planning 或 Catalog 表。V2～V15 保持不变。

### 4. 幂等写入

Repository 使用数据库唯一键仲裁并发写入。相同 tenant/order/conflicting hash 且 canonical Reservation 与 existing hash 一致时返回首次观察的既有记录；后续 replay 的新 event/correlation 不能覆盖首次证据。相同业务键但 canonical Reservation 或 existing hash 不一致时 fail closed，不覆盖历史。

不同 tenant 的相同 order/hash 互不影响。读取与写入都要求显式 tenant，Repository 不提供无 tenant 查询。

### 5. 后续 B1 语义

B1 检测到同订单不同 request hash 时：

1. 保持 canonical Reservation、Attempt、Shortage、Allocation、Movement 和 Lot 数量不变；
2. 在外层 delivery transaction 写入或复用 `ReservationRequestConflict`；
3. 可靠登记 `InventoryReservationFailedV1`，使用 canonical Reservation ID、incoming conflicting request hash 和固定 failure code；
4. 重放返回同一冲突结果，不重复事实或结果事件。

本 ADR 只提供上述证据基础，不提前实现 B1 工作流。

### 6. 迁移与审阅

V15 已进入共享 stacked merge，按仓库规则保持不可变。A2C 使用下一连续版本 V16，owner 为 `inventory`，并验证 fresh V2→V16 与 historical V15→V16。

A2C 继续使用不超过 60 个 changed files、2,200 total churn 的门禁。真实 PostgreSQL tests 不得替换为 Mock、H2 或 sleep-based concurrency。

## 后果

- 每订单唯一 canonical Reservation 和 append-only Attempt 不变量保持不变。
- hash 冲突获得独立、租户隔离、可并发幂等的业务证据。
- A2C 完成不代表 Inventory Reservation 可用；事件分类、allocation、outcome、Trade Order、API/UI 与 operations 仍由后续 stacks 交付。
- `Inventory reservation` 能力继续标记为 `Designed / In progress`。
