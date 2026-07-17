# 需求追踪矩阵

## 1. 目的

本矩阵把产品需求、领域规则、机器契约、实施任务和验证证据连接起来。实现阶段不能只在代码中“看起来支持”某项能力；每个 P0/P1 需求都应能够追踪到明确的入口、状态变化、持久化约束和测试。

状态值：

- `Designed`：设计和契约已存在，尚无可执行实现；
- `Designed / In progress`：仅完成明确的非运行基础层，完整能力仍不可用；
- `Implemented`：主行为已实现，仍可能缺少完整证据；
- `Verified`：定义的自动测试和演示证据均通过；
- `Deferred`：经明确范围决策延后，不得在 README 中作为已完成能力展示。

初始基线中下列实施状态均为 `Designed`。

## 2. 主链路追踪

| Task | 用例 | 功能需求 | 领域/架构依据 | HTTP / Event 契约 | 最低验证证据 | 初始状态 |
|---:|---|---|---|---|---|---|
| 02 | UC-IAM-001 | FR-IAM-001~005 | security/tenancy、permission matrix、ADR-006/010 | `GET /me` | JWT negative matrix、双租户隔离、React protected route | Verified |
| 03 | UC-PAR-001/002 | FR-PAR-001~006 | `partner` aggregate/state machine, `V3__partner_onboarding.sql` | `/partners*`; persisted Partner lifecycle events | `PartnerTest`、`PartnerApiIntegrationTest`、`PartnerWorkspace.test.tsx`、真实 OIDC Playwright | Verified |
| 04 | UC-CAT-001, UC-INV-001 | FR-CAT-001~004, FR-INV-001~003 | Catalog/Inventory boundaries、`V4__catalog_products_and_search_projection.sql`、`V5__inventory_supply_model.sql`、ADR-009 | `GET /catalog/skus`、`GET /catalog/skus/{skuId}` | domain/API/UI tests、真实 OIDC Playwright、12k/36k/36k PostgreSQL query plan | Verified |
| 05 | UC-QUO-001~003, UC-TRD-001 | FR-QUO-001~009, FR-TRD-001~006 | `quotation`/`tradeplanning` aggregates and policies; V6/V7 migrations; immutable partner/SKU/price/route evidence | quotation create/update/evaluate/submit/approve/issue and read-only public preview | `QuotationPricingPolicyTest` randomized money properties、`RouteEvaluationPolicyTest` golden determinism、`QuotationAggregateTest`、`QuotationApiIntegrationTest` tenant/field/concurrent decision/token cases、frontend API/form/workspace tests、real OIDC Playwright、architecture verification | Verified |
| 06 | UC-QUO-004 | FR-QUO-009~011 | quotation acceptance state/idempotency/security; `V8__customer_quotation_decisions.sql` | portal quotation + acceptance/rejection; `QuotationAcceptedV1` | domain boundary、safe DTO allow-list、20-way idempotency、expiry race、React states、refresh-safe Playwright | Verified |
| 07 | UC-ORD-001 | FR-ORD-001~004 | TradeOrder aggregate、`V9__trade_order_conversion.sql`、transactional Inbox、bounded retry、immutable snapshot | internal `/orders*` and Buyer `/buyer/orders*`; `QuotationAcceptedV1` → `TradeOrderCreatedV1` → Quotation `CONVERTED` | domain/state tests、same/different duplicate and concurrent event tests、rollback/retry/final-failure tests、tenant/Partner/field allow-list API tests、React list/detail tests、real portal-to-Buyer OIDC Playwright、`trade-order-conversion.yml` | Verified |
| 07B | UC-CAT-001, UC-INV-001 | FR-CAT-001~004, FR-INV-001~003 | `V10__inventory_quantity_unit_and_warehouse_priority.sql`、`V11__catalog_supply_projection_quantity_unit.sql`、Inventory-owned exact facts 与 Catalog-owned non-commitment projection | `GET /catalog/skus*` OpenAPI 1.5.0；quantity-unit filter、summary 与授权 exact-lot priority/version | fresh + V9 → V11 Testcontainers、PostgreSQL catalog constraints、unit grouping、role/warehouse/tenant API matrix、React tests、真实 OIDC Playwright、12k/36k/36k unit-filter plan | Verified |
| 07C-P | UC-TRD-001 | FR-TRD-001~005 | ADR-016、ROUTE-2026-03、SUPPLY-DECISION-2026-01、canonical input schema 3、Trade Planning-only V12 | REST/OpenAPI 1.5.0 shape unchanged | policy/application/hash tests、fresh + V11 → V12 Testcontainers、current/historical round trip、tamper/tenant/API field-hiding、四条既有 E2E | Verified |
| 07C-Q | UC-QUO-001~004, UC-TRD-001 | FR-QUO-001~011, FR-TRD-001~006 | ADR-017、quotation-only V13、Revision decision state、exact line identity | internal OpenAPI 1.6 Decision evidence；public DTO unchanged | domain/repository/API rollback+tamper+Legacy tests、React AUTO/FIXED/detail tests、四条既有 E2E回归 | Verified |
| 07C-PROP | UC-ORD-001, UC-INV-001 | FR-ORD-001~004, FR-INV-001~004 | ADR-018、trade_order-only V14、Current/Legacy evidence | OpenAPI 1.7；V1 events additive；Buyer hidden | Hash/presence/tamper、V13→V14、tenant/Buyer、React tests | Verified |
| 08-A1 | UC-INV-002/003 | FR-INV-010~016 | ADR-019、Reservation/Attempt/Allocation/Movement/Shortage、ExactQuantity、Request Hash | 无新增 HTTP/Event 契约 | focused domain invariants and deterministic hash tests；无 PostgreSQL correctness 声明 | Designed / In progress |
| 08-A2 | UC-INV-002/003 | FR-INV-010~016 | ADR-008/014/015/019/020、V15、fail-closed Repository、atomic Lot primitives | 无新增 HTTP/Event 契约 | fresh/upgrade migration、round-trip/tamper/tenant/version/idempotency、barrier concurrency | Implemented in review |
| 08-A2C | UC-INV-002 | FR-INV-010/016 | ADR-021、V16、`ReservationRequestConflict`、tenant-scoped immutable Repository | 无新增 HTTP/Event 契约 | domain、fresh/upgrade migration、round-trip/replay/tamper/tenant、barrier concurrency | Implemented in review |
| 08-B1 | UC-INV-002 | FR-INV-010~016 | ADR-008/013/014/018/019/020/021、outer delivery transaction、NESTED allocation savepoint | `TradeOrderCreatedV1` → `InventoryReservationConfirmedV1` / `InventoryReservationFailedV1` | Current/Legacy raw presence、deterministic FIXED/AUTO、all-or-nothing rollback、manual zero-write、request conflict、8-way PostgreSQL contention | Implemented in review |
| 08-B2 | UC-INV-002 | FR-INV-010~016 | ADR-022、Trade Order row lock、Request/Supply Decision Hash 重建、append-only outcome evidence | `InventoryReservationConfirmedV1` / `InventoryReservationFailedV1` → Order status | 同 event 与同业务 outcome 重放、8-way 并发、乱序、tenant、未知订单、hash、Legacy、终态冲突 | Implemented in review |
| 08-C | UC-INV-002/003 | FR-INV-010~016 | release/consume application operations | reservation query and order workflow | release/consume idempotency、API/UI、E2E | Designed / blocked |
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
