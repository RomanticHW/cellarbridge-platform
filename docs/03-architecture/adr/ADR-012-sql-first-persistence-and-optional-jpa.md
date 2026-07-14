# ADR-012：以 SQL-first 作为当前持久化基线，JPA 按需可选

- 状态：Accepted
- 日期：2026-07-14

## 与既有 ADR 的关系

本 ADR 补充 ADR-001 的模块化单体实现方式和 ADR-003 的数据所有权规则。它纠正旧文档中“普通聚合默认使用 JPA、仅热点路径使用 JDBC”的描述，但不推翻模块化单体、按模块 Schema 所有权、禁止跨模块表访问等既有结论。

## 背景

当前 Task 01～07 的可运行实现使用 Spring JDBC、显式 SQL 和 Flyway；构建没有引入 JPA Starter，也没有 JPA Entity。当前 Maven 构建只有一个 `backend` module，各业务模块仍是该构建内的 Spring Modulith/包边界，并未形成按业务模块拆分的物理构建隔离。把未采用的技术写成当前基线，或仅为展示技术而重写已经有事务、租户隔离和幂等证据的路径，会增加回归风险并削弱实现与文档的一致性。

## 决策

### 当前基线

- 当前持久化基线是 Spring JDBC / SQL-first，不声明已经使用 JPA。
- 显式 SQL 用于需要精确控制 tenant predicate、keyset pagination、商业快照、可靠 Publication/Inbox、读模型、原子条件更新和并发热点的路径。
- Flyway migration 是 DDL、约束、索引和 Schema 演进的权威来源；运行时代码不得用 ORM 自动建表或静默修改 Schema。
- Repository 端口属于应用边界，领域模型和 Repository 端口不得依赖 JDBC、JPA Entity 或其他持久化框架类型。
- 不为展示技术重写 Task 01～07。Inventory、可靠事件、读模型及并发热点继续使用 JDBC 和显式 SQL。

### JPA 的可选引入条件

JPA 只作为未来简单聚合的按需选项，例如写入模式简单、关联范围严格受控的 Settlement 聚合。引入时必须同时满足：

1. 有可验证的重复映射成本或维护收益证据，而不是只为统一技术栈；
2. 在当前单 `backend` Maven module 形态下，Starter 只能加入 `backend` 构建范围，并通过 Spring Modulith、ArchUnit、包可见性和适配器落点把使用范围限制到获批的 owner 业务模块；不得把这种逻辑边界描述成已经物理隔离的构建依赖；
3. 只有未来把业务模块拆成独立物理 Maven module 后，才把依赖只加入实际使用它的 module，并通过构建依赖图实施物理隔离；
4. Flyway 仍先定义并验证实际 Schema；
5. 具有真实 PostgreSQL 测试，覆盖事务回滚、tenant predicate、owner Schema、lazy loading、N+1、锁和并发行为；
6. Repository 适配器仍向领域层返回领域对象或显式投影。

### 禁止边界

- 禁止跨模块 Entity association、共享 Entity 和绕过模块语义的通用 CRUD Repository。
- 同一聚合只能有一个 canonical write path。不得在没有明确迁移阶段、对账规则、切换条件和删除计划的情况下同时用 JPA 与 JDBC 写同一聚合。
- JPA 查询不得绕过 tenant/权限谓词、模块 Schema 所有权或稳定分页规则。

## 理由

SQL-first 与当前依赖和实现事实一致，也让租户隔离、快照不可变、条件更新、事件领取和查询顺序直接可审阅。保留 JPA 的受控选项，可以在简单聚合确有收益时使用框架生产力，而不迫使高风险路径迁移。

## 后果与边界

- Repository 适配器需要显式维护 SQL 和映射，但关键数据库语义更透明，也更容易用 PostgreSQL 集成测试证明。
- 新增 JPA 不代表改变 ADR-001 或 ADR-003；若它改变模块边界、数据所有权或 canonical write path，必须另行记录决策。
- 旧文档中的 JPA 默认表述应在后续文档同步中按本 ADR 修正；在修正完成前，以本 ADR 和实际构建依赖为准。
