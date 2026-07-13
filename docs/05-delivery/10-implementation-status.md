# 实现状态

Baseline version: **Design Baseline v1.0**  
Status date: **2026-07-13**

## 1. 状态定义

- `Available`：已实现、测试并可运行；
- `Partial`：部分实现，限制明确；
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
| React frontend foundation | Available | `frontend/`; system status, readiness states, generated API types, Vitest and Playwright |
| Docker/CI executable runtime | Available | `deploy/compose/core.compose.yaml`, `scripts/smoke_core.sh`, `.github/workflows/` |
| Identity, tenant and permission access | Available | OIDC Code + PKCE, JWT validation, `/me`, two-tenant mapping/isolation and protected permission-aware navigation |
| Broader application security controls | Partial | Task 02 delivers identity boundary, safe audit logging, CORS and response headers; later threat, dependency and release evidence remains planned |
| Partner onboarding and eligibility | Available | tenant-scoped draft/review lifecycle, duplicate controls, immutable eligibility, React workspace and real OIDC Playwright flow |
| Catalog/quotation/order/inventory | Designed | implementation not started |
| Fulfillment/exception/settlement/reporting | Designed | implementation not started |
| Performance/security/release evidence | Planned | tasks 13–15 |

## 3. 声明

当前可运行范围包括 Task 01 工程骨架、Task 02 身份访问纵向切片与 Task 03 商业客户准入纵向切片：本地 Keycloak realm、双租户映射、`/me`、客户草稿、独立审核、资格版本、审计时间线和受保护控制台均可执行。商品、报价、订单等后续业务能力仍保持 `Designed`；只有具备实现、测试与运行证据的能力标记为 `Available`。

## 4. 追踪模板

实现后每行补充：

```text
Requirement IDs | source paths | test paths | CI workflow | release tag | limitations
```

没有实际证据不得标 Available。
