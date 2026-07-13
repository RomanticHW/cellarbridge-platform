# 测试架构

## 1. 测试目标

测试证明最重要的业务与架构声明，而不是追求无意义覆盖率。关键风险排序：库存超卖、重复订单、非法状态、跨租户泄露、事件丢失/重复、金额错误、恢复失败。

## 2. 测试层次

### 领域单元测试

纯 Java，无 Spring：

- 聚合状态机；
- Money/Quantity；
- 路径硬约束与评分；
- 审批策略；
- 分配策略；
- 时间边界；
- 属性/参数化测试。

### 应用测试

- 用例编排；
- 权限和错误映射；
- 事务交互通过 fake/spy ports；
- 不用过多 mock 内部实现。

### 模块集成测试

Spring Modulith test：

- 只启动目标模块与显式依赖；
- 事件发布/监听；
- 模块公开 API；
- failure/retry。

### 数据库集成测试

Testcontainers PostgreSQL（版本与生产基线一致）：

- Flyway 空库迁移；
- Repository/SQL；
- 唯一/检查约束；
- inventory 原子更新；
- 隔离/锁/回滚；
- query plan 性能基线。

不使用 H2 替代 PostgreSQL 关键语义。

### API 测试

- OpenAPI schema；
- auth/tenant/permissions；
- validation/problem details；
- idempotency；
- ETag/If-Match；
- field-level response；
- pagination/filter/sort。

### Frontend 测试

Vitest/Testing Library/Playwright，覆盖真实用户行为，避免测试内部 state 或 CSS 类。

### 系统/故障测试

Docker Compose：主旅程、事件重复、服务重启、Kafka 暂停、适配器失败、projection rebuild。

## 3. 测试金字塔与门槛

- 大量快速领域测试；
- 适量模块/DB 集成；
- 少量高价值 E2E；
- 性能/故障在指定 workflow；
- 核心领域目标行覆盖 85%/分支 75%，但不能用低质量测试填充；
- mutation testing 优先报价策略、路径、订单和库存。

## 4. 测试数据

- builders/factories 使用业务默认；
- 合成数据固定随机 seed；
- 每个测试独立 tenant/事务；
- 不共享易变全局 fixture；
- 时间使用固定 Clock；
- UUID 可注入生成器以可重现；
- 金额和数量边界包含最小/最大/舍入。

## 5. 并发测试

必须在真实 PostgreSQL：

- 并发接受/创建订单；
- 多线程/进程库存预占；
- 并发工作项领取；
- 乐观锁编辑；
- publisher 多实例 SKIP LOCKED；
- 断言最终 DB 不变量，不只断言 HTTP 状态。

测试不能依赖 `sleep` 猜时序；使用 barrier/latch 和受控故障点。

## 6. 契约测试

- OpenAPI lint + schema example validation；
- 后端 response 与契约测试；
- TypeScript client 重新生成无 diff；
- AsyncAPI/JSON Schema validation；
- producer fixture/consumer fixture；
- breaking change detection。

## 7. 架构测试

- Spring Modulith module verification；
- no cycles；
- no internal package dependency；
- domain no web/persistence/messaging dependency；
- controllers no repositories；
- module schema access lint（SQL 路径/代码扫描 + review）；
- no generic shared domain dumping ground。

## 8. 安全测试

- 双租户资源矩阵；
- 角色/权限/所有权；
- 客户字段安全；
- token validation；
- secret/log leak；
- admin/demo endpoint profile；
- dependency/container scans。

## 9. 测试命名与追踪

测试名描述业务：

```text
should_create_only_one_order_when_same_accepted_quote_event_is_delivered_concurrently
should_roll_back_all_allocations_when_one_order_line_has_insufficient_stock
```

关键测试注释/标签引用 `AC-*` 或 `FR-*`。实现状态文档记录证据路径。

## 10. 不允许

- 通过删除/禁用测试修复 CI；
- 用 H2 证明 PostgreSQL 并发；
- 对私有方法做脆弱测试；
- 在 E2E 中直接改数据库跳过业务；
- mock 掉要证明的核心规则；
- 未运行就报告通过。
