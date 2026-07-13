# 架构适应度函数

## 1. 目的

架构规则必须可重复验证，避免文档与代码逐步分离。适应度函数进入 CI，失败即阻止合并，除非有被接受的 ADR 和对应规则更新。

## 2. 模块边界

| FF ID | 规则 | 工具 |
|---|---|---|
| FF-ARC-001 | Spring Modulith 无循环和非法模块依赖 | `ApplicationModules.verify()` |
| FF-ARC-002 | 任一模块不得引用其他模块 `..internal..` | ArchUnit |
| FF-ARC-003 | domain 不依赖 web/JPA/Kafka/Redis adapter | ArchUnit |
| FF-ARC-004 | controller 不依赖 repository | ArchUnit |
| FF-ARC-005 | 公开事件/DTO 不包含 persistence entity | ArchUnit/reflection test |
| FF-ARC-006 | shared-kernel 只含白名单类型 | package rule/review |

## 3. 数据边界

| FF ID | 规则 | 验证 |
|---|---|---|
| FF-DATA-001 | 每模块迁移只创建/修改自己 schema | SQL lint/script |
| FF-DATA-002 | 无跨模块 FK | PostgreSQL catalog integration test |
| FF-DATA-003 | 所有业务表有 tenant_id（例外白名单） | catalog test |
| FF-DATA-004 | mutable aggregate 表有 version | catalog test |
| FF-DATA-005 | migration 文件合并后不可改 | checksum/CI policy |
| FF-DATA-006 | 金额列 precision/scale 符合规范 | catalog test |

## 4. 契约

- OpenAPI/AsyncAPI parse；
- examples validate；
- generated TypeScript client no diff；
- event schema compatibility；
- Problem Details error code registry complete；
- every public endpoint has operationId/security/response errors；
- every command endpoint declares idempotency/If-Match rule where needed。

## 5. 正确性

| FF ID | 声明 | 自动证据 |
|---|---|---|
| FF-COR-001 | quote → one order | unique constraint + concurrent integration test |
| FF-COR-002 | no oversell | DB constraints + concurrent test |
| FF-COR-003 | duplicate event harmless | Inbox duplicate tests |
| FF-COR-004 | commercial snapshots immutable | domain/API/persistence tests |
| FF-COR-005 | tenant isolation | two-tenant matrix tests |
| FF-COR-006 | money deterministic | property/rounding tests |

## 6. 运行与文档

- `docker compose config` 有效；
- core smoke health/login；
- README link/status validation；
- 不存在 TODO/TBD/XXX 在发布文档（允许 issue link 的明确 future work）；
- 公开仓库 secret scan；
- raw screenshots/private prompts 路径黑名单；
- Mermaid fence 和 YAML/JSON schema parse；
- docs baseline version 与 changelog/status 一致。

## 7. 性能预算

- API P95 预算由可重复 benchmark 检查（release workflow）；
- frontend bundle budget；
- query count/N+1 designated tests；
- event backlog recovery benchmark；
- inventory concurrency invariant always gating，吞吐目标为报告而非硬失败初期。

## 8. 安全门槛

- dependency high/critical vulnerability gate（允许有到期 waiver 文件）；
- container scan；
- SBOM；
- default demo credentials only demo profile；
- security headers test；
- customer schema field allowlist；
- log redaction test。

## 9. 例外机制

例外文件必须包含：rule ID、原因、风险、owner、创建/到期日、修复 issue。过期例外使 CI 失败。不可通过注释掉测试或扩大白名单偷偷绕过。
