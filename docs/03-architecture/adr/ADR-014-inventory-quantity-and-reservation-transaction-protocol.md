# ADR-014：库存数量、候选分配与预占事务协议

- 状态：Accepted；日期：2026-07-14

**背景与关系：** 本 ADR 补充 ADR-008，冻结 Task 08 前置协议；库存预占仍是 Designed，并未实现。ADR-008 的 P1 整单全成全败、不超卖及不依赖 JVM/Redis lock 继续有效。

## 数量与供给模式

Lot、Reservation 请求和 allocation 都保存 `quantityUnit`；P1 只按相同 `CASE`/`BOTTLE` 分配，不自动换算。仅 `DOMESTIC_ON_HAND`、`BONDED_ON_HAND`、`HONG_KONG_ON_HAND` 自动预占，pool 类型须与事件冻结的 `line.supplyType` 相同。`supplyPoolId != null` 是 `FIXED_POOL`：指定 pool 必须匹配 tenant、SKU、单位、route、类型和有效状态，不合格以 `INVENTORY_FIXED_POOL_INELIGIBLE` 整单失败且禁止回退；空值是 `ROUTE_ELIGIBLE_AUTO`：只选同 tenant、route、SKU、单位、类型的 active pool/warehouse 与 `AVAILABLE` lot，可跨 lot、不可跨 route。
`IN_TRANSIT_PRESALE`、`OVERSEAS_SOURCING` 是 manual type：整单不进入数量 savepoint，不修改 lot 或保留 allocation，以 `MANUAL_CONFIRMATION_REQUIRED` / `SUPPLY_NOT_AUTOMATICALLY_RESERVABLE` 保存可见结果；不足、并发失败或 fixed pool 不合格不得伪装成 manual。Inventory 不读 Catalog、Quotation 或 Trade Order 内部表；入站事件须携带 SKU、数量、单位、供给模式、供给池、supply type、route 和稳定业务标识等决策快照，缺失或冲突按永久契约错误处理。

## 前置数据与确定性

Inventory readiness 的首个前向迁移 V10 只修改 `inventory`：`inventory_lot.quantity_unit` 先 nullable，按既有 synthetic lot 的 CASE 语义回填、验证无空值，再加 NOT NULL/CHECK 且不保留 default；`warehouse.allocation_priority integer NOT NULL DEFAULT 100 CHECK (allocation_priority >= 0)`，数值越小越优先，seed 显式写值。迁移测试覆盖空库 V2～V10 和带 lot 的 V9 升级。优先级修改受 tenant/warehouse、仓库管理权限、optimistic version 与 old/new/actor/time/reason 审计约束，只影响新 attempt；人工 retry 使用当时有效值并冻结候选顺序和 priority 值/版本证据，不重排已确认 allocation。
订单行全序是 `sku_id ASC, quantity_unit ASC, order_line_id ASC`。候选 lot 同时满足 tenant、SKU、单位、pool policy、route、active pool/warehouse、`AVAILABLE`、`available_from <= decision_time` 或空及自动类型，并按 `warehouse.allocation_priority ASC, available_from ASC NULLS LAST, received_at ASC NULLS LAST, lot_code ASC, lot_id ASC` 处理；DB 查询与应用迭代都保留顺序，禁止未排序集合，最终 pool、lot、数量及 priority 值/版本证据冻结到 allocation。

## 原子更新与失败分类

每个 chunk 使用同一条件 UPDATE，绑定 tenant、lot、pool、SKU、状态、单位，并要求 `on_hand_quantity - reserved_quantity >= :allocation_quantity`；增加 reserved、更新时间/人员/version 后 affected rows 必须为 1。禁止先汇总后无条件更新，也禁止 JVM、Redis 或 distributed lock 作为正确性防线。事件 delivery 使用外层本地事务，allocation 使用 NESTED/savepoint：成功时提交全部 lot、allocation、Reservation/Attempt、后继事件和 Inbox；自动类型不足或条件竞争回滚 savepoint，再在外层保存 FAILED、append-only Attempt、shortage/冲突，发布 `InventoryReservationFailedV1` 并完成 Inbox，订单据此进入 `RESERVATION_FAILED`。manual 结果使用空 shortages 和 `SUPPLY_NOT_AUTOMATICALLY_RESERVABLE`，fixed-pool 失败使用 `INVENTORY_FIXED_POOL_INELIGIBLE`，均不得互相伪装或 fallback；错误码须同步失败事件契约。deadlock、serialization failure、数据库不可用或 unexpected SQL 回滚外层事务并走有限技术重试，绝不转换成 shortage/业务事件。

## 幂等、重试、释放与消费

Reservation 以 `(tenant_id, order_id)` 唯一并保存 canonical `requestHash`；hash 至少覆盖 orderId、route 及每行的 orderLineId、skuId、quantity、quantityUnit、supplyPoolId、supplyType。相同请求返回完整原结果且不新增副作用，不同 hash 在副作用前以高严重度冲突。Attempt 只追加，attemptNumber 单调递增并保存 requestHash、triggerType、起止时间、outcome、failureCode、shortage snapshot 和 correlation/causation。人工 retry 须有明确权限和新幂等键，禁止无限自动 retry；其 command 以 `(tenant_id, reservation_id, business_key)` 唯一，hash 覆盖 reservation/order、trigger、输入快照和参数，`trigger_type` 不扩大唯一键。command、Attempt、完整结果与 publication 同事务；并发冲突后锁定重读，同 hash 返回原 attempt/结果，不同 hash 在任何副作用前整体冲突，技术异常不留下半成品且既有 Attempt 永不修改。
Release/consume command 以 `(tenant_id, reservation_id, action_type, business_key)` 唯一，hash 覆盖按 `allocation_id ASC` 排序的完整 allocation ID、quantity 和 unit 集合；same key + same hash 返回完整原结果，same key + different hash 在任何数量变化前冲突。每个 allocation 的 movement/action 使用稳定 business key，但不得替代 command 级唯一键或允许部分成功；command、守恒更新、movement 与 lot 条件更新同事务。Release 要求 allocation `remaining_reserved >= quantity` 且 lot `reserved_quantity >= quantity`，同量减少二者、不改 on-hand；consume 还要求 `on_hand_quantity >= quantity`，同量减少 `remaining_reserved`、reserved 和 on-hand。任一 affected rows 不为 1 全部回滚；禁止超量、重复扣减或负数，多 allocation 沿订单行和 lot 预占全序、以 allocation ID 破同序并按同一方向加锁。始终满足 `allocated = released + consumed + remainingReserved`，Reservation 汇总与 allocation 一致。

**后果：** Inventory readiness 交付负责 V10、单位、优先级、OpenAPI/UI 的具体权限、字段可见性、optimistic concurrency、审计交互及迁移测试；边界完成前保持 Planned。Task 08 才实现预占、事件及真实并发/回滚/幂等/守恒测试，此前不得标为 Available。shortage 摘要只是失败时的观察证据，供给变化只能通过受控 retry 重评；P1 不支持部分预占、跨单位换算、跨 route 兜底或未来供给自动承诺。
