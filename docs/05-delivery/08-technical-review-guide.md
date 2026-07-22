# 技术审阅与面试说明

## 1. 项目主张

CellarBridge 用一个现实 B2B 贸易场景集中展示：DDD、模块化单体、事务/并发、幂等、可靠事件、租户安全、React 业务 UI、测试与可观测性。

它不主张：真实公司官方系统、完整法规、真实支付/WMS、生产多区域能力。

## 2. 推荐审阅路径

### 后端开发岗位

README → aggregates/invariants → order source/tests → Inventory ADR/design → events → API → commits。

### 架构岗位

scenario selection → context map → ADRs → transaction/event/security → fitness functions → failure reports。

### 全栈岗位

user journey/page specs → OpenAPI generated client → delivered feature code → `frontend/e2e/demo.spec.ts`；dashboard、audit 与完整异常恢复均可运行。

### 30 秒简历描述

“设计并实现 CellarBridge，一套 Java 21/Spring Boot/React 的模块化 B2B 贸易协同平台。v1.0.0 覆盖多租户准入、可解释报价、幂等转订单、PostgreSQL 原子库存预占、履约异常恢复、应收与审计报表，并以真实数据库并发、完整浏览器旅程和可观测/供应链门禁提供复验证据。”

只在相应功能实际 Available 后使用“实现”。

## 3. 审阅问题与证据

| 问题 | 证据 |
|---|---|
| 为什么模块化单体？ | ADR-001、dependency tests |
| 如何避免大泥球？ | internal、schema ownership、Modulith/ArchUnit |
| 如何避免重复订单？ | unique quote、Inbox、concurrency test |
| 如何避免超卖？ | ADR-014、`JdbcAtomicInventoryLotRepository` 条件 SQL、savepoint 与真实 PostgreSQL 并发测试 |
| 事件丢了怎么办？ | 本地 publication/Inbox 同事务、有限重试、倒序积压与重复投递证据；外部 broker 不在当前运行拓扑 |
| 重复事件呢？ | Inbox unique + handler transaction |
| 客户为何看不到成本？ | separate DTO/schema + security tests |
| 路径推荐是否黑盒？ | reason codes, score breakdown, policy version |
| 为什么不用 ES/微服务？ | ADRs + capacity evidence |
| React 有什么价值？ | 完整 `demo.spec.ts` 覆盖角色切换、query state、并发/幂等回执、冲突、失败恢复、报表和字段边界 |

## 4. 诚实边界

面试中明确：

- 公司资料来自公开/用户提供招聘介绍；
- 业务模块是合理抽象，非真实内部实现；
- 路径和金额规则为合成 Demo；
- performance 只针对记录环境；
- full production gaps 见部署文档。

这种边界增强可信度。

## 5. 代码审阅体验

- README 首屏清楚；
- `make validate`、`make test`、`make dev-core`、`make smoke-core`、`make order-e2e`；
- docs links；
- requirement IDs；
- 业务 package；
- tests name可读；
- commits 讲故事；
- release evidence；
- 不留内部执行 prompt 或草稿噪音。
