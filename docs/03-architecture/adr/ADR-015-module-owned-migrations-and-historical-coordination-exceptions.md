# ADR-015：模块所有的 Migration 与历史协调例外

- 状态：Accepted
- 日期：2026-07-14

## 与既有 ADR 的关系

本 ADR 澄清（Clarifies）ADR-003 的 migration 文件所有权和自动验证方式。ADR-003 的按模块 Schema 所有权、禁止跨模块 FK 和禁止直接跨模块表访问继续有效。

## 背景

已合并的 `V8__customer_quotation_decisions.sql` 同时包含 Quotation 与 `platform_event` DDL；`V9__trade_order_conversion.sql` 同时协调 Identity & Access、Trade Order、Quotation 与 `platform_event` DDL。Flyway migration 合并后不可修改、拆分或重命名，因此需要明确历史例外，同时阻止后续任务把该先例扩展成常态。

## 决策

### 历史边界

- 仓库实际 migration 文件从 V2 开始，当前为 V2～V9；这些既有文件不可修改、拆分或重命名。既有迁移治理规则中的“不修改 V1～V9”不意味着仓库存在 V1 migration 文件。
- V8、V9 是不可修改、不可拆分、不可重命名的多 owner 历史协调 migration。
- 该例外仅表示一个 migration 文件触及多个 owner Schema，不是跨模块外键例外。V8、V9 中的 FK 仍只连接同一 owner Schema 内的表；跨模块关系只保存逻辑 ID 和必要快照。
- 仓库既有 V2～V9 文件及其 checksum 属于不可改写的 Flyway 历史；后续修正一律追加新 migration。

### V10 起的文件所有权

- 从 V10 起，每个 migration 文件只能创建或修改一个 owner Schema 的对象。
- 一个 Task 需要协调多个 Schema 时，必须按 owner Schema 拆成多个连续版本；版本顺序表达兼容的前向协调过程，不能把多 owner DDL 重新合并进一个文件。
- `platform_event` 归 platform event support 所有，publication、Inbox、external outbox 及其索引、约束和函数都按该 owner 独立迁移。
- 所有版本都禁止跨模块 FK。跨边界只保存逻辑 ID 和维持历史语义所需的不可变快照。

### 自动验证

ownership manifest 必须全量覆盖磁盘上的每个 V2+ migration，并为每项记录 version、file、owner Schemas 和文件 SHA-256。V8、V9 以固定 `legacyException` 加 ADR-015 引用声明其多 owner 集合；CI 只允许这两个既定例外，并校验磁盘文件全覆盖、无多余 manifest 项、SHA-256 一致且例外集合恰好为 V8/V9。从 V10 起，owner Schemas 必须且只能有一个，并禁止任何 exception/waiver。

CI 使用高信号 Schema statement scanner 检查 Schema-qualified DDL，以及 `INSERT`、`UPDATE`、`DELETE`、`MERGE`、`TRUNCATE`、`COPY` 等 DML/backfill 是否都只作用于 manifest 声明的 owner。scanner 是针对仓库 migration 约定的保护，不宣称是完整 SQL parser。

`DO`/`EXECUTE`、dynamic SQL、`search_path`、非限定对象或其他无法可靠归属的语句必须 fail closed；人工审查只能拒绝或要求改写为可归属语句，不能把未识别语句作为绕过。scanner/manifest 测试至少包含同 owner backfill 的正向用例、跨 owner DML 的反向用例，以及 V8/V9 固定例外和 V10+ 单 owner 约束用例。

所有 migration 应在真实 PostgreSQL 上执行 catalog test；最终 Catalog 必须不存在引用不同 owner Schema 的 foreign key。文本扫描负责文件所有权，Catalog test 负责最终数据库结构，两者不可互相替代。

### 无法拆分时的停止条件

V8/V9 不得作为新多 owner 文件的先例。本 ADR 生效期间，V10+ 的唯一 owner 是硬规则，任何新 migration 都不得写入多个 owner Schema。跨 Schema feature 必须拆成多个连续、前向兼容的单 owner migration；若无法安全拆分，必须在编写或执行 DDL 前停止，由 superseding ADR 重新评估并获批。只有新 ADR 明确取代本 ADR 后规则才可能改变，当前 manifest 和审查流程都不得以 waiver 或多 owner 声明绕过本规则；无论如何仍不得改写 V2～V9 历史或放宽跨模块 FK 禁令。

## 理由

单 owner 文件让 migration 评审、回滚影响分析和模块提取边界更清晰。保留 V8/V9 checksum 尊重 Flyway 历史，而 manifest、语句扫描和 PostgreSQL Catalog 验证组合起来，可以在不虚构完整 SQL 解析能力的前提下提供高信号门禁。

## 后果与边界

- 跨模块纵向切片可能需要多个连续 migration 版本，提交顺序和向前兼容设计必须更明确。
- ownership manifest 和 scanner 规则本身需要测试；新增 DDL 写法时必须先证明 scanner 能识别或明确拒绝。
- Catalog test 只证明最终 FK 边界，不能证明文件只修改单一 owner，因此仍必须保留 manifest 和 statement scanner。
