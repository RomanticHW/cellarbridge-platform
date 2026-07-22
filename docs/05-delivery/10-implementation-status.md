# 实现状态

Baseline version: **Design Baseline v1.0**  
Status date: **2026-07-22**

## 1. 状态定义

- `Available`：已实现、测试并可运行；
- `Partially available`：部分实现，限制明确；
- `Designed`：完整设计/契约可用，尚无实现；
- `Planned`：仅路线图；
- `Not planned`：明确非范围。

## 2. 当前状态

| Capability                                       | Status                      | Evidence                                                                                                                                                                                                                                                                      |
| ------------------------------------------------ | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Research/evidence boundary                       | Available                   | `docs/00-research`                                                                                                                                                                                                                                                            |
| Product requirements/journeys                    | Available                   | `docs/01-product`                                                                                                                                                                                                                                                             |
| DDD model/state/events                           | Available                   | `docs/02-domain`                                                                                                                                                                                                                                                              |
| Architecture/ADRs                                | Available                   | `docs/03-architecture`                                                                                                                                                                                                                                                        |
| OpenAPI/AsyncAPI/JSON Schema                     | Available (design contract) | `contracts/`                                                                                                                                                                                                                                                                  |
| Repository engineering rules                     | Available                   | `AGENTS.md`, governance docs                                                                                                                                                                                                                                                  |
| Java backend foundation                          | Available                   | `backend/`; Spring context, PostgreSQL/Flyway, Actuator, Modulith and ArchUnit tests                                                                                                                                                                                          |
| Persistence baseline                             | Available                   | Spring JDBC / SQL-first；JPA 未安装且仅为 ADR-012 约束的未来可选项                                                                                                                                                                                                            |
| Reliable local events                            | Available                   | custom `event_publication`, local dispatcher, Consumer Inbox, bounded retry and transaction integration tests                                                                                                                                                                 |
| React frontend foundation                        | Available                   | `frontend/`; system status, readiness states, generated API types, Vitest and Playwright                                                                                                                                                                                      |
| Docker/CI executable runtime                     | Available                   | `deploy/compose/core.compose.yaml`, `scripts/smoke_core.sh`, `.github/workflows/`                                                                                                                                                                                             |
| Identity, tenant and permission access           | Available                   | OIDC Code + PKCE, JWT validation, `/me`, two-tenant mapping/isolation and protected permission-aware navigation                                                                                                                                                               |
| Broader application security controls            | Available                   | STRIDE、tenant × role × ownership × state × field evidence matrix、safe headers/CORS/404/rate limit、日志脱敏、secret/dependency/container gates                                                                                                                            |
| Partner onboarding and eligibility               | Available                   | tenant-scoped draft/review lifecycle, duplicate controls, immutable eligibility, React workspace and real OIDC Playwright flow                                                                                                                                                |
| Catalog and supply search                        | Available                   | Catalog/Inventory model, FTS + trigram search, field/warehouse permissions, React workspace, real OIDC E2E and reproducible benchmark                                                                                                                                         |
| Inventory unit readiness                         | Available                   | CASE/BOTTLE Lot 与 Catalog projection 分离；Warehouse priority/version 仅在授权 exact-lot 视图可见；fresh seed、V9 → V11 保留性、约束、权限、E2E 与双单位 benchmark 证据                                                                                                      |
| Quotation and trade planning                     | Available                   | revisioned snapshots/pricing, ROUTE-2026-03 route-bound supply evidence, independent approval, issue token, customer-safe preview, React workspace, Testcontainers and real OIDC E2E                                                                                          |
| Route supply-decision Planning evidence          | Available                   | ROUTE-2026-03 single-source eligibility/confidence, one microsecond evaluation time, canonical input schema 3, V12 selected-route evidence and historical reads                                                                                                               |
| Quotation Supply Decision freeze                 | Available                   | V13 quotation-owned copy, exact line identity, AUTO/FIXED, original Evaluation issue verification, Legacy gates, OpenAPI 1.6 and React evidence                                                                                                                               |
| Supply Decision propagation                      | Available                   | Current/Legacy V1、FROZEN/LEGACY_UNVERIFIED Order、V14、OpenAPI 1.7/AsyncAPI 1.2                                                                                                                                                                                              |
| Customer quotation decision                      | Available                   | controlled portal context, strict customer DTO, accept/reject idempotency, immutable decision, leased expiry work, durable `QuotationAcceptedV1`, React receipt and refresh-safe E2E                                                                                          |
| Quote-to-order conversion                        | Available                   | transactional Inbox consumer, immutable Trade Order snapshot, unique quotation conversion, reliable `TradeOrderCreatedV1`, eventual Quotation link, tenant/Buyer-scoped query UI and real OIDC E2E                                                                            |
| Inventory reservation                            | Available                   | B1/B2 原子预占与订单 outcome；C1 V17 command/audit、actor-scoped request hash、Reservation/Allocation 锁、NESTED savepoint 与条件 Lot SQL；C2 tenant-scoped Reservation API、warehouse-assignment exact 投影、React Order workbench、组件测试与真实 OIDC/PostgreSQL E2E       |
| Fulfillment orchestration                        | Available                   | V18 路线模板与冻结快照；依赖动作、版本并发、幂等与 SLA；模拟适配器；Trade Order 联动；OpenAPI/事件契约；React board/detail/customer milestones；PostgreSQL、组件和 Playwright 证据                                                                                            |
| Exception Center and recovery                    | Available                   | V19 去重 case/occurrence/history/recovery/work-item；库存失败、履约失败/逾期与技术投递检测；源状态验证的库存重试、履约重试/恢复和 publication 重放；权限/租户/掩码边界；OpenAPI 1.10/generated client；React queue/detail；PostgreSQL、组件与真实 Playwright 证据             |
| Settlement receivables and payments              | Available                   | V20 版本化触发策略、订单商业快照、唯一应收、`numeric(19,4)` 余额、不可变付款/多次部分冲正、逾期批锁、可靠事件；OpenAPI 1.11/AsyncAPI 1.4/generated client；Finance/Buyer/Auditor/System Operator 边界；React queue/detail/dialog；PostgreSQL、组件与真实 OIDC Playwright 证据 |
| Audit/reporting dashboards                       | Available                   | V21 generation/inbox/checkpoint、不可变 audit、安全 timeline、乱序保护与 pending；dashboard/work queue/audit API；OpenAPI 1.12/AsyncAPI 1.5/generated client；ECharts cards/tooltip/table fallback；PostgreSQL、组件与真实 OIDC Playwright 证据                               |
| Architecture fitness functions                   | Partially available         | Modulith、domain/controller/public-contract、Catalog/Inventory 单位与 migration 核心规则已执行；shared-kernel、运行和性能门禁仍按 fitness status 分为 Partially available/Planned                                                                                             |
| OTel/Tempo/Prometheus/Grafana full profile       | Available                   | ADR-025、Micrometer/OTLP、event span links、scheduler/adapter observations、ECS JSON、versioned provisioning/dashboard/alerts 与 full compose；Kafka/Redis 未引入                                                                                                            |
| ECharts dashboards                               | Available                   | ECharts 6 模块化加载、ARIA/decal、tooltip、可访问名称与表格 fallback；loading/empty/error/stale 状态                                                                                                                                                                          |
| Security and supply-chain evidence               | Available                   | threat model、authorization matrix、CycloneDX、secret/dependency/Grype gates、non-root/read-only runtime evidence；生产安全认证与签名发布仍属 Task 15                                                                                                                        |

## 3. 声明

当前基线已在 Quotation、Current/Legacy Event 和 Trade Order 中保存一致决策证据；Task 08～12 的 Inventory、Fulfillment、Exception、Settlement 与 Audit/Reporting 均已可运行。Task 13 增加不参与业务正确性的 trace/metric/log 与安全供应链门禁。结算模块仍只记录经授权录入的外部付款事实；报表最终一致，页面显示 `dataAsOf` 与 projection status。当前实现不引入 Kafka、Redis、独立仓库、搜索引擎或 BI 平台。

## 4. 追踪模板

实现后每行补充：

```text
Requirement IDs | source paths | test paths | CI workflow | release tag | limitations
```

没有实际证据不得标 Available。
