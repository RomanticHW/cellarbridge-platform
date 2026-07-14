# 架构适应度函数

## 1. 目的

架构规则必须可重复验证，避免文档与代码逐步分离。下表区分已进入 CI 的规则、部分门禁和计划能力；只有 `Available` 才表示当前合并门禁已执行。

## 2. 模块边界

| FF ID | 规则 | 状态 | 当前证据/计划工具 |
|---|---|---|---|
| FF-ARC-001 | Spring Modulith 无循环和非法模块依赖 | Available | `ApplicationModules.verify()` |
| FF-ARC-002 | 任一模块不得引用其他模块 `..internal..` | Available | ArchUnit |
| FF-ARC-003 | domain 不依赖 Spring/Jakarta/web/application/infrastructure | Partially available | 当前 ArchUnit 只覆盖部分框架；完整规则尚待实现 |
| FF-ARC-004 | controller 不依赖 repository | Planned | ArchUnit 规则尚待实现 |
| FF-ARC-005 | 公开事件/DTO 不包含 persistence entity | Planned | ArchUnit/reflection test 尚待实现 |
| FF-ARC-006 | shared-kernel 只含白名单类型 | Partially available | package review；完整自动白名单待补 |

## 3. 数据边界

| FF ID | 规则 | 状态 | 当前证据/计划验证 |
|---|---|---|---|
| FF-DATA-001 | V10+ 每个 migration 只修改一个 owner Schema | Planned | manifest + statement scanner |
| FF-DATA-002 | 无跨模块 FK | Planned | PostgreSQL catalog integration test |
| FF-DATA-003 | 所有业务表有 tenant_id（例外白名单） | Planned | catalog test |
| FF-DATA-004 | mutable aggregate 表有 version | Planned | catalog test |
| FF-DATA-005 | migration 文件合并后不可改 | Partially available | Flyway 历史；完整 SHA-256 manifest 门禁待补 |
| FF-DATA-006 | 金额/数量列 precision/scale 符合规范 | Planned | catalog test |

## 4. 契约

- **Available**：OpenAPI/AsyncAPI parse、examples validate、generated TypeScript types no diff；
- **Partially available**：event schema/fixture compatibility；
- **Planned**：Problem Details error code registry completeness、endpoint metadata completeness、command idempotency/If-Match 自动检查。

## 5. 正确性

| FF ID | 声明 | 状态 | 自动证据 |
|---|---|---|---|
| FF-COR-001 | quote → one order | Available | unique constraint + concurrent integration test |
| FF-COR-002 | no oversell | Planned | Task 08 DB constraints + concurrent test |
| FF-COR-003 | duplicate event harmless | Available | Inbox duplicate tests |
| FF-COR-004 | commercial snapshots immutable | Available | domain/API/persistence tests |
| FF-COR-005 | tenant isolation | Available | two-tenant matrix tests |
| FF-COR-006 | money deterministic | Available | property/rounding tests |

## 6. 运行与文档

- **Available**：`docker compose config`、README link validation、公开仓库 secret scan、YAML/JSON parse；
- **Partially available**：core smoke、私有路径黑名单、Mermaid fence（不等于 Mermaid 编译）、README/baseline/changelog status 一致性；
- **Planned**：发布文档 future-work 结构化校验。

## 7. 性能预算

- **Partially available**：Catalog 查询 benchmark；
- **Planned**：API P95 release gate、frontend bundle budget、query count/N+1 designated tests、event backlog recovery benchmark、inventory concurrency invariant。

## 8. 安全门槛

- **Available**：security headers、customer-safe schema field allowlist；
- **Partially available**：依赖审计、demo profile 凭据隔离和日志脱敏测试；
- **Planned**：统一 high/critical vulnerability gate、container scan、SBOM 和覆盖所有日志入口的统一脱敏门禁。

## 9. 例外机制

例外文件必须包含：rule ID、原因、风险、owner、创建/到期日、修复 issue。过期例外使 CI 失败。不可通过注释掉测试或扩大白名单偷偷绕过。
