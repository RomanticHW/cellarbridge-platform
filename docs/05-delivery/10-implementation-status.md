# 实现状态

Baseline version: **Design Baseline v1.0**  
Status date: **2026-07-16**

## 1. 状态定义

- `Available`：已实现、测试并可运行；
- `Partially available`：部分实现，限制明确；
- `Designed`：完整设计/契约可用，尚无实现；
- `Planned`：仅路线图；
- `Not planned`：明确非范围。

## 2. 当前状态

| Capability | Status | Evidence |
|---|---|---|
| Research/evidence boundary | Available | `docs/00-research` |
| Product requirements/journeys | Available | `docs/01-product` |
| DDD model/state/events | Available | `docs/02-domain` |
| Architecture/ADRs | Available | `docs/03-architecture` |
| OpenAPI/AsyncAPI/JSON Schema | Available (design contract) | `contracts/` |
| Repository engineering rules | Available | `AGENTS.md`, governance docs |
| Java backend foundation | Available | `backend/`; Spring context, PostgreSQL/Flyway, Actuator, Modulith and ArchUnit tests |
| Persistence baseline | Available | Spring JDBC / SQL-first；JPA 未安装且仅为 ADR-012 约束的未来可选项 |
| Reliable local events | Available | custom `event_publication`, local dispatcher, Consumer Inbox, bounded retry and transaction integration tests |
| React frontend foundation | Available | `frontend/`; system status, readiness states, generated API types, Vitest and Playwright |
| Docker/CI executable runtime | Available | `deploy/compose/core.compose.yaml`, `scripts/smoke_core.sh`, `.github/workflows/` |
| Identity, tenant and permission access | Available | OIDC Code + PKCE, JWT validation, `/me`, two-tenant mapping/isolation and protected permission-aware navigation |
| Broader application security controls | Partially available | Task 02 delivers identity boundary, safe audit logging, CORS and response headers; later threat, dependency and release evidence remains planned |
| Partner onboarding and eligibility | Available | tenant-scoped draft/review lifecycle, duplicate controls, immutable eligibility, React workspace and real OIDC Playwright flow |
| Catalog and supply search | Available | Catalog/Inventory model, FTS + trigram search, field/warehouse permissions, React workspace, real OIDC E2E and reproducible benchmark |
| Inventory unit readiness | Available | CASE/BOTTLE Lot 与 Catalog projection 分离；Warehouse priority/version 仅在授权 exact-lot 视图可见；fresh seed、V9 → V11 保留性、约束、权限、E2E 与双单位 benchmark 证据 |
| Quotation and trade planning | Available | revisioned snapshots/pricing, ROUTE-2026-03 route-bound supply evidence, independent approval, issue token, customer-safe preview, React workspace, Testcontainers and real OIDC E2E |
| Route supply-decision Planning evidence | Available | ROUTE-2026-03 single-source eligibility/confidence, one microsecond evaluation time, canonical input schema 3, V12 selected-route evidence and historical reads |
| Quotation Supply Decision freeze | Available | V13 quotation-owned copy, exact line identity, AUTO/FIXED, original Evaluation issue verification, Legacy gates, OpenAPI 1.6 and React evidence |
| Supply Decision propagation | Available | Current/Legacy V1、FROZEN/LEGACY_UNVERIFIED Order、V14、OpenAPI 1.7/AsyncAPI 1.1；Task 08 执行层未实施 |
| Customer quotation decision | Available | controlled portal context, strict customer DTO, accept/reject idempotency, immutable decision, leased expiry work, durable `QuotationAcceptedV1`, React receipt and refresh-safe E2E |
| Quote-to-order conversion | Available | transactional Inbox consumer, immutable Trade Order snapshot, unique quotation conversion, reliable `TradeOrderCreatedV1`, eventual Quotation link, tenant/Buyer-scoped query UI and real OIDC E2E |
| Inventory reservation | Designed / In progress | A1 domain 已合并；A2 的 Inventory-owned V15、fail-closed Repository、乐观版本和原子 Lot reserve/release/consume 原语已实现并处于 review；A2C 正为同订单不同 request hash 增加不可变冲突证据；订单工作流、事件、API/UI 与 E2E 仍留在 B/C，订单保持 `PENDING_RESERVATION` |
| Fulfillment/exception/settlement/reporting | Designed | implementation not started |
| Architecture fitness functions | Partially available | Modulith、domain/controller/public-contract、Catalog/Inventory 单位与 migration 核心规则已执行；shared-kernel、运行和性能门禁仍按 fitness status 分为 Partially available/Planned |
| Kafka/Redis/OTel/Prometheus/Grafana full profile | Planned | no current runtime dependency or compose service |
| ECharts dashboards | Planned | ECharts is not installed；dashboard slice remains Task 12 |
| Performance/security/release evidence | Planned | tasks 13–15 |

## 3. 声明

当前基线已在 Quotation、Current/Legacy Event 和 Trade Order 中保存一致的决策证据，客户与 Buyer DTO 仍严格隐藏内部证据。库存预占处于 `Designed / In progress`：A1 领域与 A2 持久化/原子 Lot 原语已实现并处于 review，A2C 正在补充 request-hash 冲突证据，但没有事件消费、订单级事务编排、outcome、API/UI 或 E2E；`PENDING_RESERVATION` 仍是明确的未完成状态。

## 4. 追踪模板

实现后每行补充：

```text
Requirement IDs | source paths | test paths | CI workflow | release tag | limitations
```

没有实际证据不得标 Available。
