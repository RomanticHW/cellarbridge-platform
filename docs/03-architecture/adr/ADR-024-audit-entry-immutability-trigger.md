# ADR-024：审计条目的数据库不可变约束

- 状态：Accepted；日期：2026-07-22

## 背景

ADR-015 要求 V10 起的 migration 由单一 Schema 所有，并让 statement scanner 拒绝 procedural SQL。Task 12 同时要求审计条目在数据库层拒绝普通 update/delete；PostgreSQL 的行级拒绝 trigger 需要一个返回 `trigger` 的函数，单靠表约束或静态 trigger 声明无法实现该行为。

## 决策

V21 仍只由 `audit_reporting` Schema 所有，不引入跨 Schema 对象或外键。允许它声明唯一一个固定文本的 `audit_reporting.prevent_audit_entry_mutation()` PL/pgSQL trigger function，并仅绑定到 `audit_reporting.audit_entry` 的 `BEFORE UPDATE OR DELETE` trigger。函数体只能抛出固定 SQLSTATE `55000`，不得读写数据、执行动态 SQL、设置 `search_path` 或接受参数。

门禁对该函数进行逐字匹配并在移除这一固定块后继续用 ADR-015 scanner 检查 V21 的全部其余语句。Manifest 将 V21 标记为 `ADR-024`；任何其他 migration、函数体、trigger 目标或 procedural SQL 都不会由本 ADR 放行。

## 后果

`audit_entry` 的普通 update/delete 在数据库层失败，投影器仍只能追加 allow-list 审计事实。该例外范围比通用 procedural SQL 放行更窄，不改变单 owner、无跨 Schema FK、迁移历史不可改写及运行时事件重放约束。
