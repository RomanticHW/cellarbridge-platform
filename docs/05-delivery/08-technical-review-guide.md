# 技术审阅与面试说明

## 1. 项目主张

CellarBridge 用一个现实 B2B 贸易场景集中展示：DDD、模块化单体、事务/并发、幂等、可靠事件、租户安全、React 业务 UI、测试与可观测性。

它不主张：真实公司官方系统、完整法规、真实支付/WMS、生产多区域能力。

## 2. 推荐审阅路径

### 后端开发岗位

README → aggregates/invariants → order/inventory source/tests → events → API → commits。

### 架构岗位

scenario selection → context map → ADRs → transaction/event/security → fitness functions → failure reports。

### 全栈岗位

user journey/page specs → OpenAPI generated client → feature code → E2E → dashboard。

### 30 秒简历描述

“设计并实现 CellarBridge，一套 Java 21/Spring Boot/React 的模块化 B2B 贸易协同平台。以可解释路径评估、幂等报价转订单、PostgreSQL 原子库存预占和可靠事件为核心，配套多租户安全、Testcontainers 并发测试、OpenAPI/AsyncAPI 与可观测性。”

只在相应功能实际 Available 后使用“实现”。

## 3. 审阅问题与证据

| 问题 | 证据 |
|---|---|
| 为什么模块化单体？ | ADR-001、dependency tests |
| 如何避免大泥球？ | internal、schema ownership、Modulith/ArchUnit |
| 如何避免重复订单？ | unique quote、Inbox、concurrency test |
| 如何避免超卖？ | conditional SQL、transaction、DB check、test |
| 事件丢了怎么办？ | publication/outbox、restart test、reconcile |
| 重复事件呢？ | Inbox unique + handler transaction |
| 客户为何看不到成本？ | separate DTO/schema + security tests |
| 路径推荐是否黑盒？ | reason codes, score breakdown, policy version |
| 为什么不用 ES/微服务？ | ADRs + capacity evidence |
| React 有什么价值？ | full operable flow, query state, conflict/recovery |

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
- `make doctor/up/verify/demo`；
- docs links；
- requirement IDs；
- 业务 package；
- tests name可读；
- commits 讲故事；
- release evidence；
- 不留内部执行 prompt 或草稿噪音。
