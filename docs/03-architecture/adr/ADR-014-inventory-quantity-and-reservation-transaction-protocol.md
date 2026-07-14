# ADR-014：库存数量、候选分配与预占事务协议

- 状态：Accepted
- 日期：2026-07-14

## 与既有 ADR 的关系

本 ADR 是 Task 08 的前置设计，不代表库存预占已经可运行。它补充并在协议层取代 ADR-008 未细化的数量、候选选择、失败持久化和幂等规则；ADR-008 的 P1 全成全败、不超卖以及不依赖 JVM/Redis lock 的核心结论继续有效。

## 背景

当前 Inventory 只提供批次和供给查询，订单停留在 `PENDING_RESERVATION`，尚无 Reservation 运行闭环。Task 08 必须先固定单位、供给池选择、稳定顺序、数据库竞争结果和失败分类，否则同一订单可能因隐式换算、跨路线回退或异常误分类产生不可重放的不同结果。

## 决策

### 数量单位与供给模式

- `InventoryLot` 的在手、已预占和可用数量必须携带 `quantityUnit`；Reservation 请求和 allocation 同样保存单位。
- P1 只允许同单位分配。`CASE` 与 `BOTTLE` 不自动换算；未来换算必须有独立、版本化的包装换算协议。
- 处理前必须先按事件冻结的每个 `line.supplyType` 对整单逐行分类。只有 `DOMESTIC_ON_HAND`、`BONDED_ON_HAND`、`HONG_KONG_ON_HAND` 三种 on-hand 类型可以进入 `FIXED_POOL` 或 `ROUTE_ELIGIBLE_AUTO`；候选 pool 及候选 lot 所属 pool 的 supply type 必须与该行冻结值精确相等。
- 自动类型的订单行在 `supplyPoolId != null` 时采用 `FIXED_POOL`：只检查指定 pool。pool 和 lot 必须同时匹配 tenant、SKU、quantity unit、订单 route 和有效状态；任一条件不满足时禁止回退到其他 pool，并以稳定业务失败码 `INVENTORY_FIXED_POOL_INELIGIBLE` 进入整单失败协议，不得改写为 `MANUAL_CONFIRMATION_REQUIRED`。
- 自动类型的订单行在 `supplyPoolId == null` 时采用 `ROUTE_ELIGIBLE_AUTO`：只从订单 route 匹配的 active pool 选择。可跨多个同单位、同 supply type 的 lot 分配，但不得跨 route。
- 只有 `IN_TRANSIT_PRESALE` 和 `OVERSEAS_SOURCING` 是 manual type。任一行属于这两类时，整单不进入数量 savepoint，不修改库存、不保留 allocation，并产生订单级 `MANUAL_CONFIRMATION_REQUIRED` 可见人工处理结果；可以附带逐行原因，但禁止部分占用。候选量不足、并发条件更新失败或 fixed pool 不合格都不是 manual type。

Inventory 不读取 Catalog、Quotation 或 Trade Order 的内部表。`TradeOrderCreatedV1` 等入站事件必须携带 SKU、数量、单位、供给模式、供给池、supply type、route 和稳定业务标识等决策所需快照；缺少或互相冲突时按不可恢复契约错误处理。

### 确定性候选集与顺序

本 ADR 修订并在候选排序前缀上取代既有 `InventoryAllocationPolicy`：先按仓库优先级排序，再沿用预计可用日期、收货时间、批次代码和 UUID 的稳定顺序。`allocation_priority` 是批准的硬决策，Task 08 不得删除或绕过。

Task 08 的首个 Inventory migration（V10）必须在 `inventory.warehouse` 增加 `allocation_priority integer NOT NULL DEFAULT 100 CHECK (allocation_priority >= 0)`；数值越小优先级越高，`100` 是初始中性默认值。所有 demo seed 必须显式写出该值或经审阅的业务优先级，不能依赖未记录的物理行顺序。

修改 `allocation_priority` 必须同时满足 tenant 与 warehouse scope、库存仓库管理权限和 optimistic version，并追加包含 old value、new value、actor、time、reason 的审计记录。变更只影响其提交后开始的新 Reservation attempt，不重排已确认 allocation；显式人工 retry 创建的新 attempt 使用当时有效的优先级，并冻结本次候选顺序及 priority 值/版本证据。

V9 升级到 V10 时，`inventory.inventory_lot.quantity_unit` 必须按以下顺序演进：先新增 nullable 列且不设置 default；再按当前所有 synthetic lot 都按 `CASE` 展示和解释的硬编码语义，把全部既有 lot 回填为 `CASE` 并确认无空值；最后增加 `NOT NULL` 和只允许 `CASE`/`BOTTLE` 的 `CHECK`。完成后不得保留永久 default，新写入必须显式提供单位。migration 测试必须同时覆盖从空库执行 V2～V10，以及带有既有 lot 的 V9 数据库升级、回填和约束生效。

候选 lot 必须同时满足：

- tenant、SKU 和 quantity unit 匹配；
- `FIXED_POOL` 或 `ROUTE_ELIGIBLE_AUTO` 的 pool policy 匹配，且 pool supply type 与事件冻结的 `line.supplyType` 精确相等；
- route 匹配，supply pool 与 warehouse 均为 active；
- lot 状态为 `AVAILABLE`；
- `available_from` 为空或不晚于本次预占的统一决策时间；
- supply type 属于三个自动可预占类型，候选 lot 不得通过其他 supply type 的 pool 混入。

订单行固定按 `sku_id ASC, quantity_unit ASC, order_line_id ASC` 处理。每行候选 lot 固定按以下顺序处理：

```text
warehouse.allocation_priority ASC,
inventory_lot.available_from ASC NULLS LAST,
inventory_lot.received_at ASC NULLS LAST,
inventory_lot.lot_code ASC,
inventory_lot.id ASC
```

数据库查询和应用迭代都必须保留该顺序；禁止依赖未排序的 DB/JVM 集合迭代。

### 原子更新与事务失败协议

每个 lot allocation chunk 必须在同一条条件原子 UPDATE 中增加预占量并校验同一个 chunk，至少绑定 tenant、lot、pool、SKU、状态和单位：

```sql
UPDATE inventory.inventory_lot
   SET reserved_quantity = reserved_quantity + :allocation_quantity,
       updated_at = :now,
       updated_by = :actor_id,
       version = version + 1
 WHERE tenant_id = :tenant_id
   AND id = :lot_id
   AND supply_pool_id = :supply_pool_id
   AND sku_id = :sku_id
   AND status = 'AVAILABLE'
   AND quantity_unit = :quantity_unit
   AND on_hand_quantity - reserved_quantity >= :allocation_quantity;
```

UPDATE 的 affected rows 必须为 `1` 才算该 chunk 成功。跨多个 lot 分配时，每个 chunk 都必须使用自己的 `:allocation_quantity` 执行同一协议；禁止先汇总可用量后无条件更新，也禁止用 JVM lock、Redis lock 或 distributed lock 作为正确性防线。

一次事件 delivery 使用外层本地事务；实际 allocation 在 `NESTED` 事务/savepoint 中完成：

- manual type 在进入数量 savepoint 前停止数量处理，不修改任何 lot，也不留下其他行的 allocation；但外层事务必须可靠保存 `FAILED` Reservation、append-only `ReservationAttempt` 和 `failureCode=MANUAL_CONFIRMATION_REQUIRED` 的可见人工处理结果，发布既有 `InventoryReservationFailedV1` 并完成 Consumer Inbox。该事件以既有 `reasonCode=SUPPLY_NOT_AUTOMATICALLY_RESERVABLE` 和空 shortages 明确表达 manual/non-shortage 分类；Trade Order 按既有失败事实进入 `RESERVATION_FAILED`。不得把 manual 原因伪装成 shortage，也不得为此引入新事件或新状态。
- 三种自动类型进入 allocation savepoint。候选量不足，或任一条件 UPDATE 因并发竞争 affected rows 不为 `1`，都必须回滚 savepoint，撤销整单全部数量变更和 allocation；随后在外层事务可靠保存 `FAILED` Reservation、append-only `ReservationAttempt`、shortage/冲突摘要，发布 `InventoryReservationFailedV1` 并完成 Consumer Inbox。Order 消费该失败事实后进入 `RESERVATION_FAILED`，不得返回 manual 结果。
- fixed pool 不合格同样按稳定码 `INVENTORY_FIXED_POOL_INELIGIBLE` 保存整单失败并发布 `InventoryReservationFailedV1`；不得 fallback，也不得写成 manual 结果。Task 08 落地前必须把该码同步到错误目录和失败事件契约。
- deadlock、serialization failure、数据库不可用和 unexpected SQL error 回滚外层事务，由事件基础设施按技术瞬态失败执行有限重试；这些错误绝不转换为 shortage 或业务失败事件。

### 幂等、释放与消费

- `ReservationAttempt` 只追加，不覆盖历史尝试。
- `Reservation` 以 `(tenant_id, order_id)` 唯一，保存规范化 `requestHash`。相同首次预占请求返回已有结果且不新增副作用；同一订单收到不同 hash 时返回冲突。
- 经授权 retry 是独立幂等命令，其 command record 以 `(tenant_id, reservation_id, business_key)` 唯一；`trigger_type` 只记录批准重试的业务触发来源并纳入 canonical `requestHash`，不能扩大唯一键范围。不同重试意图必须使用不同 `businessKey`。命令必须保存 canonical `requestHash`，hash 至少覆盖 reservation/order、trigger、输入快照和重试参数。
- retry command record、新增 `ReservationAttempt`、完整结果和后继 publication 必须在同一个外层本地事务提交。并发唯一冲突后必须重读并锁定既有 command：hash 相同时返回同一个已提交 attempt 及完整原结果，hash 不同时在任何业务副作用前整体冲突，绝不追加第二个 attempt。技术异常回滚整个事务，不得留下 command、attempt、结果或 publication 半成品；既有 attempt 永不修改。
- release/consume command record 以 `(tenant_id, reservation_id, action_type, business_key)` 唯一。canonical `requestHash` 必须覆盖完整且按 `allocation_id ASC` 排序的 allocation ID、quantity、unit 集合；同一唯一键和相同 hash 返回该 command 的完整原结果，hash 不同必须在任何数量变化前使整条 command 冲突。
- 每个 allocation 的 movement/action key 只作为同一 command 事务内的明细防线，不得替代 command 级唯一键或允许部分 allocation 独立成功。
- command record、全部 allocation 守恒更新、movement 明细和 lot 条件更新必须在同一事务完成。allocation 更新必须以 `remaining_reserved >= :action_quantity` 为条件，lot 更新必须以 `reserved_quantity >= :action_quantity` 为条件，consume 还必须校验 `on_hand_quantity >= :action_quantity`；allocation 与 lot 的每次条件 UPDATE affected rows 都必须为 `1`。任一条件竞争失败时回滚整个 command，禁止双扣或留下半完成记录。
- release 只按 `:action_quantity` 同量减少 allocation 的 `remaining_reserved` 和 lot 的 `reserved_quantity`，不修改 `on_hand_quantity`；consume 按同一数量同时减少 allocation 的 `remaining_reserved`、lot 的 `reserved_quantity` 和 `on_hand_quantity`。重复调用不得重复释放、重复消费或使任一数量为负。
- 一个 command 涉及多个 allocation 时，必须沿用预占的全局稳定顺序：先按订单行全序，再按 warehouse/lot 全序，最后以 `allocation_id ASC` 破同序；所有路径按同一方向取得行锁并执行条件更新。
- 任一 allocation 始终满足：

```text
allocated = released + consumed + remainingReserved
```

Reservation 汇总必须与全部 allocation 的守恒结果一致。

## 理由

单位、供给范围和全序共同保证相同快照产生可解释的候选顺序；条件 UPDATE 和 savepoint 则把并发竞争结果交给 PostgreSQL，并允许业务不足在同一次可靠消费中留下可观察失败事实，而技术故障仍保持可重试。

## 后果与边界

- Task 08 必须增加 V10+ migration、事件契约、领域/应用实现和真实 PostgreSQL 并发、回滚、幂等与守恒测试，完成前不得把 Inventory reservation 标为 Available。
- Task 08 的第二个 PR 必须在 OpenAPI 和 UI 中定义 `allocation_priority` 的具体权限码、字段可见性、optimistic concurrency 和审计交互；这些边界落地前，仓库优先级管理不得标为 Available。
- shortage 摘要是失败时的观察证据，不是永久库存快照；后续供给变化只能通过受控重试重新评估。
- P1 不支持部分预占、跨单位换算、跨 route 兜底或未来供给自动承诺。
