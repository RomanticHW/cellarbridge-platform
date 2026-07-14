# ADR-012：以 SQL-first 作为当前持久化基线，JPA 按需可选

- 状态：Accepted；日期：2026-07-14

**背景与关系：** 本 ADR 补充 ADR-001/ADR-003。Task 01～07 的 Available 实现使用 Spring JDBC、显式 SQL 与 Flyway，没有 JPA Starter 或 Entity；业务模块位于单一 `backend` Maven module，以 Spring Modulith 和包边界隔离，旧文档中的“JPA 默认”不代表实现事实。

## 决策

当前基线保持 SQL-first，不为展示技术重写既有模块。显式 SQL 继续承载 tenant predicate、keyset pagination、商业快照、Publication/Inbox、读模型、库存条件更新和并发热点；Flyway 是 DDL、约束、索引及 Schema 演进的权威来源，禁止 ORM 自动建表或静默改 Schema。Repository 端口属于应用边界，领域和端口不暴露 JDBC、JPA Entity 或其他持久化框架类型；Inventory、可靠事件、读模型与并发热点继续使用 JDBC。
JPA 仅是未来简单聚合（例如 Settlement）的可选方案：引入须有可验证收益，由 Flyway 先定义 Schema，并以真实 PostgreSQL 覆盖事务、租户、Schema、lazy loading、N+1、锁和并发；适配器只返回领域对象或显式投影。单一 Maven module 下 Starter 只能加入 `backend`，范围由 Spring Modulith、ArchUnit、包可见性和适配器边界约束，只有物理拆分后才可声明构建依赖隔离。禁止跨模块 Entity association、共享 Entity、通用 CRUD 绕过模块语义，以及查询绕过租户、权限、所有权或稳定分页；同一聚合只能有一个 canonical write path，JPA/JDBC 双写须先有迁移阶段、对账、切换和删除计划。
**后果：** 显式映射成本换取透明、可审阅的数据库语义和 PostgreSQL 证据。JPA 若改变模块边界、数据所有权或 canonical write path，必须另立 ADR；README 与技术基线按实际依赖标注 Available/Planned。
