# 架构适应度函数

## 1. 目的

架构规则必须可重复验证，避免文档与代码逐步分离。下表区分已进入 CI 的规则、部分门禁和计划能力；只有 `Available` 才表示当前合并门禁已执行。

## 2. 模块边界

| FF ID | 规则 | 工具 |
|---|---|---|
| FF-ARC-001 | Spring Modulith 无循环和非法模块依赖 | `ApplicationModules.verify()` |
| FF-ARC-002 | 任一模块不得引用其他模块 `..internal..` | ArchUnit |
| FF-ARC-003 | domain 不依赖 Spring/Jakarta/web/application/infrastructure | ArchUnit + 反向 fixture |
| FF-ARC-004 | controller 不依赖 repository | ArchUnit |
| FF-ARC-005 | 公开事件/DTO 不包含 persistence entity | ArchUnit |
| FF-ARC-006 | shared-kernel 只含白名单类型 | package rule/review |

状态：FF-ARC-001～005 **Available**；FF-ARC-006 **Partially available**，完整自动白名单待补。

## 3. 数据边界

| FF ID | 规则 | 验证 |
|---|---|---|
| FF-DATA-001 | V10+ 每个 migration 只修改一个 owner Schema | manifest + 高信号 statement scanner |
| FF-DATA-002 | 无跨模块 FK | PostgreSQL catalog integration test |
| FF-DATA-003 | 所有业务表有 tenant_id（例外白名单） | catalog test |
| FF-DATA-004 | mutable aggregate 表有 version | catalog test（显式 mutable root 集合） |
| FF-DATA-005 | migration 文件合并后不可改 | current-tree SHA-256 manifest + CI Git-history base/head gate |
| FF-DATA-006 | 金额/数量列 precision/scale 符合规范 | catalog test（numeric 分类与命名约束） |
| FF-DATA-007 | inventory quantity unit / allocation priority | Inventory readiness PR 的 V10 与 catalog test |

状态：FF-DATA-001～006 **Available**；FF-DATA-007 **Planned**。

## 4. 契约

- OpenAPI/AsyncAPI parse；
- examples validate；
- generated TypeScript types no diff；
- event schema compatibility；
- Problem Details error code registry complete；
- every public endpoint has operationId/security/response errors；
- every command endpoint declares idempotency/If-Match rule where needed。

状态：前三项及 QuotationAccepted Schema 的 producer/consumer fixture **Available**；其余完整性门禁 **Planned**。

## 5. 正确性

| FF ID | 声明 | 自动证据 |
|---|---|---|
| FF-COR-001 | quote → one order | unique constraint + concurrent integration test |
| FF-COR-002 | no oversell | DB constraints + concurrent test |
| FF-COR-003 | duplicate event harmless | Inbox duplicate tests |
| FF-COR-004 | commercial snapshots immutable | domain/API/persistence tests |
| FF-COR-005 | tenant isolation | two-tenant matrix tests |
| FF-COR-006 | money deterministic；zero-price order invariant | domain/event/rounding tests |

状态：FF-COR-001、003～006 **Available**；FF-COR-002 **Planned**，由 Task 08 提供真实并发证据。

## 6. 运行与文档

- `docker compose config` 有效；
- core smoke health/login；
- README link/status validation；
- 不存在 TODO/TBD/XXX 在发布文档（允许 issue link 的明确 future work）；
- 公开仓库 secret scan；
- raw screenshots/private prompts 路径黑名单；
- Mermaid fence 和 YAML/JSON schema parse；
- docs baseline version 与 changelog/status 一致。

状态：compose、README link、secret scan、YAML/JSON parse **Available**；core smoke、私有路径、Mermaid 与跨文档状态校验 **Partially available**；future-work 结构化校验 **Planned**。

## 7. 性能预算

- API P95 预算由可重复 benchmark 检查（release workflow）；
- frontend bundle budget；
- query count/N+1 designated tests；
- event backlog recovery benchmark；
- inventory concurrency invariant always gating，吞吐目标为报告而非硬失败初期。

状态：Catalog 查询 benchmark **Partially available**；其余 **Planned**。

## 8. 安全门槛

- dependency high/critical vulnerability gate（允许有到期 waiver 文件）；
- container scan；
- SBOM；
- default demo credentials only demo profile；
- security headers test；
- customer schema field allowlist；
- log redaction test。

状态：security headers 与 customer-safe field allowlist **Available**；依赖审计、demo profile 和日志脱敏 **Partially available**；统一漏洞门禁、container scan 与 SBOM **Planned**。

## 9. 例外机制

例外文件必须包含：rule ID、原因、风险、owner、创建/到期日、修复 issue。过期例外使 CI 失败。不可通过注释掉测试或扩大白名单偷偷绕过。
