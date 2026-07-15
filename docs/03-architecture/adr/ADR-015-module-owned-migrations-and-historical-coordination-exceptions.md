# ADR-015：模块所有的 Migration 与历史协调例外

- 状态：Accepted；日期：2026-07-14

**背景与关系：** 本 ADR 澄清 ADR-003。仓库 migration 从 V2 开始；已发布的 V8 协调 `quotation`/`platform_event`，V9 协调 `identity_access`/`trade_order`/`quotation`/`platform_event`。Flyway 历史不可修改、拆分或重命名，但不能成为新多 owner 文件的先例。

## 决策

V2～V9 的文件名、owner 集合与 SHA-256 冻结，修正只能追加；V8/V9 是仅有的多 owner 历史协调例外，不是跨 Schema FK 例外。V10 起每个 migration 恰好一个 owner Schema、无 waiver；跨 Schema feature 拆成多个连续、前向兼容版本，`platform_event` 及 publication/Inbox/external outbox 独立归其 owner。所有版本禁止跨模块 FK，边界关系保存逻辑 ID 与必要不可变快照；若无法安全拆分，须在 DDL 前停止并由 superseding ADR 决策，不得扩大白名单。
门禁分为不可互相替代的两层：current-tree manifest integrity 全量覆盖磁盘 V2+，记录 version、file、owner Schemas、legacyException 和 SHA-256；Git-history migration immutability 使用显式 base/head 的 `scripts/validate_migration_history.py` 阻止已发布 V*.sql 的修改、删除、重命名、复制和类型变化，Manifest 同步修改不能抵消历史违规。只有 V8/V9 可引用 ADR-015 并声明上述精确 owner 集合；严格 statement scanner 仅作用于 V10+：Schema-qualified DDL/DML/backfill 只能触及唯一 owner，procedural/dynamic SQL、`search_path`、注释、未知或未限定对象及无法可靠归属的结构 fail closed，同 owner 静态 trigger 允许。它是高信号检查，不宣称完整 SQL parser。
scanner fixture 覆盖同 owner backfill/静态 trigger 正例，以及 procedural、dynamic、跨 owner、未限定对象反例；真实 PostgreSQL catalog test 执行全历史并独立验证无跨 Schema FK，两类证据不能互相替代。
**后果：** 跨模块切片需要明确的连续迁移顺序，manifest/scanner 随新 SQL 写法维护。Catalog 只能证明最终结构，不能替代文件所有权检查；任何情况下都不得改写 V2～V9 或放宽跨模块 FK。
