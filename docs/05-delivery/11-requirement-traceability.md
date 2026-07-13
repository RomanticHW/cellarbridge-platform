# 需求追踪矩阵

## 1. 目的

本矩阵把产品需求、领域规则、机器契约、实施任务和验证证据连接起来。实现阶段不能只在代码中“看起来支持”某项能力；每个 P0/P1 需求都应能够追踪到明确的入口、状态变化、持久化约束和测试。

状态值：

- `Designed`：设计和契约已存在，尚无可执行实现；
- `Implemented`：主行为已实现，仍可能缺少完整证据；
- `Verified`：定义的自动测试和演示证据均通过；
- `Deferred`：经明确范围决策延后，不得在 README 中作为已完成能力展示。

初始基线中下列实施状态均为 `Designed`。

## 2. 主链路追踪

| Task | 用例 | 功能需求 | 领域/架构依据 | HTTP / Event 契约 | 最低验证证据 | 初始状态 |
|---:|---|---|---|---|---|---|
| 02 | UC-IAM-001 | FR-IAM-001~005 | security/tenancy、permission matrix、ADR-006/010 | `GET /me` | JWT negative matrix、双租户隔离、React protected route | Designed |
| 03 | UC-PAR-001/002 | FR-PAR-001~006 | Partner aggregate/state machine/invariants | `/partners*`; Partner lifecycle events | aggregate transitions、self-review deny、tenant/API/Playwright | Designed |
| 04 | UC-CAT-001, UC-INV-001 | FR-CAT-001~004, FR-INV-001~003 | Catalog/Inventory boundaries、ADR-009 | `GET /catalog/skus` | PostgreSQL query plan、search/permission/UI tests | Designed |
| 05 | UC-QUO-001~003, UC-TRD-001 | FR-QUO-001~009, FR-TRD-001~006 | Quotation/Trade Planning aggregates, policies, snapshots | quotation create/update/evaluate/submit/approve/issue | money properties、route golden cases、approval/field/E2E | Designed |
| 06 | UC-QUO-004 | FR-QUO-009~011 | quotation acceptance state/idempotency/security | portal quotation + acceptance; `QuotationAcceptedV1` | safe DTO allow-list、expiry race、concurrent acceptance | Designed |
| 07 | UC-ORD-001 | FR-ORD-001~004 | TradeOrder aggregate、reliable publication、Inbox | orders query; `QuotationAcceptedV1` → `TradeOrderCreatedV1` | unique order、duplicate event、crash-point recovery | Designed |
| 08 | UC-INV-002/003 | FR-INV-010~016 | Inventory aggregate、ADR-008、atomic SQL | reservation query; reservation outcome events | concurrent no-oversell、all-or-nothing、release/consume idempotency | Designed |
| 09 | UC-FUL-001/002 | FR-FUL-001~005 | versioned templates、Fulfillment state machine | fulfillment list/detail/actions; completion/failure events | dependency/concurrency/SLA/customer visibility/E2E | Designed |
| 10 | UC-EXC-001 | FR-EXC-001~004 | Exception aggregate、recovery catalog、failure model | exception query/recovery; `ExceptionOpenedV1` | dedup、recovery verification、safe replay、permissions | Designed |
| 11 | UC-SET-001 | FR-SET-001~004 | Receivable aggregate、money/reversal rules | receivables/payments/reversal; `ReceivableCreatedV1` | money properties、external-reference idempotency、overdue/reversal | Designed |
| 12 | UC-AUD-001, UC-REP-001 | FR-AUD-001/002, FR-REP-001/002, FR-NOT-001~003 | audit model、event projections、read-model rules | dashboard/audit endpoints | duplicate/out-of-order/rebuild/tenant/field/chart tests | Designed |

## 3. 横切质量追踪

| Area | 主要需求 | 设计依据 | 实施任务 | 必须保留的公开证据 |
|---|---|---|---:|---|
| 模块边界 | NFR-MOD-* | module rules、Spring Modulith、ArchUnit | 01 onward | architecture test output and module diagram |
| 事务与幂等 | NFR-REL-* | transaction, event, idempotency docs | 05~11 | duplicate/concurrency/crash tests |
| 多租户与授权 | NFR-SEC-* | security/tenancy and permission matrix | 02 onward, harden 13 | role × tenant × ownership × state matrix |
| 性能 | NFR-PER-* | performance/scalability | 04, 08, 12, 14 | dataset, environment, scripts, p50/p95/p99 and invariant checks |
| 可观测性 | NFR-OBS-* | observability | 13 | trace walkthrough, dashboards, metric definitions, redaction tests |
| 可恢复性 | NFR-REL-* | resilience/failure model | 07~10, 14 | replay/retry/backlog/restart evidence |
| 前端质量 | NFR-UX-* | frontend/testing architecture | every vertical slice | loading/error/forbidden/conflict tests and Playwright flow |
| 供应链安全 | NFR-SEC-* | security and release strategy | 13/15 | SBOM, dependency/container scan and secret scan summary |
| 可评审性 | NFR-MNT-* | reviewer guide、DoD、release plan | all | coherent commits, traceability, README status and demo script |

具体 NFR 编号与目标见 `docs/01-product/08-non-functional-requirements.md`；若实现改变编号或范围，必须在同一 PR 更新本矩阵。

## 4. 实现时的更新规则

每个纵向任务合并时：

1. 将实际完成的行更新为 `Implemented` 或 `Verified`；
2. 填入代码、migration、API/event、测试和演示的相对路径；
3. 未运行的测试不能使状态成为 `Verified`；
4. 部分实现应拆分需求或保持 `Implemented` 并写明缺口；
5. 任何 `Deferred` 必须有 scope/ADR/issue 依据；
6. README 的能力状态不得高于本矩阵和 implementation status 中较低者。

## 5. 评审抽样法

技术评审者可任选一行，例如 Task 08，然后沿以下路径检查：

```text
FR-INV-010~016
→ inventory aggregate invariants
→ ADR-008
→ reservation API/event schemas
→ Flyway constraints and atomic SQL
→ application/adapter code
→ concurrent Testcontainers test
→ React reservation view
→ performance/failure evidence
```

若任一环节只能靠口头说明而无法定位，应视为追踪缺口并在合并前修正。
