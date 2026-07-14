# 实现状态

Baseline version: **Design Baseline v1.0**  
Status date: **2026-07-14**

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
| Quotation and trade planning | Available | revisioned snapshots/pricing, deterministic explainable route policy, independent approval, issue token, customer-safe read-only preview, React workspace, Testcontainers and real OIDC E2E |
| Customer quotation decision | Available | controlled portal context, strict customer DTO, accept/reject idempotency, immutable decision, leased expiry work, durable `QuotationAcceptedV1`, React receipt and refresh-safe E2E |
| Quote-to-order conversion | Available | transactional Inbox consumer, immutable Trade Order snapshot, unique quotation conversion, reliable `TradeOrderCreatedV1`, eventual Quotation link, tenant/Buyer-scoped query UI and real OIDC E2E |
| Inventory reservation | Designed | orders intentionally remain `PENDING_RESERVATION`; atomic allocation and reservation outcome handling remain Task 08 |
| Fulfillment/exception/settlement/reporting | Designed | implementation not started |
| Architecture fitness functions | Partially available | Modulith cycles/internal boundaries and selected ArchUnit rules run now；remaining domain/controller/public-contract/catalog/migration rules are tracked in the fitness status table |
| Kafka/Redis/OTel/Prometheus/Grafana full profile | Planned | no current runtime dependency or compose service |
| ECharts dashboards | Planned | ECharts is not installed；dashboard slice remains Task 12 |
| Performance/security/release evidence | Planned | tasks 13–15 |

## 3. 声明

当前可运行范围包括 Task 01 工程骨架、Task 02 身份访问、Task 03 商业客户准入、Task 04 酒款/供给检索、Task 05 报价/贸易路径、Task 06 客户安全报价决定，以及 Task 07 幂等报价转订单。客户接受后，本地 at-least-once 消费器以事务 Inbox 和数据库唯一约束保证最多一个业务订单，可靠记录 `TradeOrderCreatedV1`，并由 Quotation 独立消费该事实、最终链接订单。Buyer 只能通过身份映射的 Partner scope 读取客户安全 DTO；库存预占仍保持 `Designed`，订单的 `PENDING_RESERVATION` 是明确的未完成状态。只有具备实现、测试与运行证据的能力标记为 `Available`。

## 4. 追踪模板

实现后每行补充：

```text
Requirement IDs | source paths | test paths | CI workflow | release tag | limitations
```

没有实际证据不得标 Available。
