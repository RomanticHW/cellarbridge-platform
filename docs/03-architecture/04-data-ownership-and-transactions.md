# 数据所有权与事务

## 1. Schema 所有权

下表是当前与目标模块共同遵守的 ownership map，不是现有物理表清单。V2～V9 已实现 Identity、Partner、Catalog/Inventory supply、Quotation/Trade Planning、Trade Order 与 `platform_event`；Fulfillment、Exception、Settlement、Audit/Reporting 的代表表仍为 Designed。

| Schema | 所有者 | 代表表 |
|---|---|---|
| `identity_access` | identity-access | tenant, user_access, role_binding |
| `partner` | partner | partner, contact, route_eligibility, review_decision |
| `catalog` | catalog | wine_product, sku, producer, region |
| `inventory` | inventory | warehouse, supply_pool, inventory_lot；A2 reservation/attempt/allocation/movement/shortage persistence |
| `quotation` | quotation | quotation, revision, line, approval, acceptance |
| `trade_planning` | trade-planning | route_definition, policy_version, evaluation, candidate_result |
| `trade_order` | trade-order | trade_order, order_line, cancellation |
| `fulfillment` | fulfillment | template, plan, step, dependency, milestone, adapter_attempt |
| `exception_center` | exception-center | exception_case, assignment, note, recovery_attempt |
| `settlement` | settlement | receivable, payment_record |
| `audit_reporting` | audit-reporting | audit_entry, timeline_projection, metric_projection, checkpoint |
| `platform_event` | platform event support | publication, inbox；external_outbox 为 Planned full profile |

模块数据库用户/权限在本地可简化为一个应用账号，但通过代码/测试/迁移路径执行所有权。full hardening 可为 schema 配置数据库权限。

## 2. 跨模块引用

- 保存 UUID 和必要快照；
- 不创建跨 schema FK；
- 不使用 ORM association 跨模块；
- 源对象删除以停用为主；
- 读模型可组合事件投影，但不反向修改源表；
- 需要实时验证时调用公开 API，而不是 join。

## 3. 事务边界

### 本地事务

- 一个应用命令通常只写一个模块 schema；
- 同模块多个聚合仅在业务原子性明确时同事务，例如库存预占；
- 业务变更、Inbox 处理结果和可靠发布记录同事务；
- 事务内不执行不受控网络调用。

### 跨模块

- 使用可靠事件和幂等消费者；
- 允许临时状态（例如 ACCEPTED 无订单、ORDER 待预占）；
- 对账任务识别超时并恢复；
- UI 明确显示处理中/失败，不假装同步完成。

## 4. 隔离级别

默认 PostgreSQL `READ COMMITTED`，配合：

- 乐观版本控制普通聚合；
- 唯一约束处理幂等；
- 原子条件 UPDATE 处理库存；
- 必要时 `SELECT ... FOR UPDATE SKIP LOCKED` 用于发布/工作队列领取；
- 不全局提升 SERIALIZABLE；若局部使用必须有基准和重试设计。

## 5. 库存 SQL 语义

状态：**Task 08 C1 implemented in review**。B1 已编排订单级原子预占；C1 在 Reservation 行锁和确定性 Allocation 顺序下，以 NESTED savepoint 编排 release/consume、append-only Movement、命令幂等结果与审计。公开 API/UI/E2E 仍属于 C2，不能据此声明完整能力 Available。

示意：

```sql
UPDATE inventory.inventory_lot
SET reserved_quantity = reserved_quantity + :quantity,
    updated_by = :actor_id,
    version = version + 1,
    updated_at = :now
WHERE tenant_id = :tenant_id
  AND id = :lot_id
  AND supply_pool_id = :supply_pool_id
  AND sku_id = :sku_id
  AND quantity_unit = :quantity_unit
  AND status = 'AVAILABLE'
  AND on_hand_quantity - reserved_quantity >= :quantity;
```

受影响行为 1 才成功。C1 的多 Allocation 操作在同一 savepoint 中全成全败；业务拒绝由外层事务保存稳定命令结果和审计，技术失败回滚整个命令。数量精度、单位、Movement 和余额守恒均由领域、SQL 与真实 PostgreSQL 测试共同约束。

## 6. 乐观并发

- 可编辑业务对象包含 `version`；
- API 使用 ETag/If-Match 或请求 expectedVersion；
- 更新 WHERE 包含 version；
- 冲突返回当前版本与可安全公开摘要；
- 不在服务端盲目重放用户输入覆盖他人决定；
- 幂等重试与并发冲突是不同概念。

## 7. 迁移

- 当前仓库 migration 从 V2 到 V9 使用简单递增版本；V8/V9 是冻结的多 owner 历史协调例外；
- 从 V10 起，一个文件只允许修改一个 owner Schema；跨模块任务拆为多个连续、前向兼容的 migration；
- ownership manifest 已覆盖全部 V2+ 文件并记录 SHA-256；V10+ 另由高信号语句 scanner 和 PostgreSQL catalog test 验证，历史差异仍需 PR 基线门禁/评审；
- 合并后不可修改；
- schema 建立、约束和索引在迁移中；
- 大数据迁移拆 expand/backfill/contract；
- 种子数据与结构迁移分离；
- CI 对空库和上一发布快照执行迁移；
- rollback 使用向前修复，不承诺自动 down migration。

## 8. 备份与恢复（演示范围）

- 提供 `pg_dump`/restore 脚本和验证说明；
- 合成 demo 数据可重建；
- 事件/投影重建说明清晰；
- 不声称完成生产 PITR/跨地域灾备；
- 恢复测试记录版本和校验结果。

## 9. 数据增长

- audit/event/metric 表按时间索引；达到规模证据后考虑分区；
- 列表默认 keyset 或稳定排序分页；
- 大 payload 不写普通日志；
- 读模型避免请求时跨 schema 大 join；
- retention 见数据分类文档。
