# 测试策略与验证计划

## 1. 风险驱动优先级

| 风险 | 证明方式 |
|---|---|
| 库存超卖 | PostgreSQL 条件 SQL + 100/500 并发测试 + DB 方程 |
| 重复订单 | unique quote + 并发/重复事件 |
| 跨租户泄露 | 角色/tenant/资源矩阵 integration |
| 事件丢失/重复 | crash window、replay、Inbox |
| 非法状态 | aggregate/state transition parameterized tests |
| 金额错误 | property/rounding/currency tests |
| 客户看到内部字段 | dedicated schema + serialization/API tests |
| 恢复造成重复副作用 | recovery idempotency/failure injection |

## 2. CI 分层

### Fast（每 PR）

- docs/contracts lint；
- backend format/compile/domain unit；
- Modulith/ArchUnit；
- frontend lint/type/unit；
- secret scan。

### Integration（每 PR，可并行）

- PostgreSQL Testcontainers migrations/repositories；
- API security；
- module event integration；
- selected Playwright smoke。

### Extended（main/nightly/release）

- full E2E；
- high concurrency；
- Kafka/Redis/OTel full profile；
- performance；
- failure injection/restart；
- mutation tests；
- image scan/SBOM。

## 3. 测试目录建议

```text
backend/src/test/java/.../module/...        unit/application
backend/src/test/java/.../integration/...   DB/API/module
backend/src/test/java/.../architecture/...  boundaries
backend/src/testFixtures/...                synthetic builders
frontend/src/**/__tests__/
frontend/e2e/
performance/k6-or-gatling/
scripts/failure-tests/
```

性能工具在 Task 14 评估 k6/Gatling/JMH：JMH 只测纯算法，不用于端到端数据库吞吐。

## 4. 验收映射

`implementation-status.md` 维护：Requirement → test class/spec → CI workflow → last release evidence。测试改名时同步。

## 5. Testcontainers

- PostgreSQL 18.x 与基线一致；
- Kafka full integration 使用 Testcontainers/compose designated suite；
- container reuse 不作为 CI 前提；
- migration 从空库；
- 每测试 tenant/清理策略稳定；
- 不 mock 要证明的数据库约束。

## 6. E2E

主路径拆为稳定场景：

1. partner approval；
2. quote create/route/approval；
3. customer accept/idempotent；
4. order/reservation；
5. fulfillment failure/recovery；
6. payments/dashboard。

使用 UI 正常 API，不直接写数据库；seed API/fixture 仅建立前置且只 test/demo profile。

## 7. Flaky 管理

- 不盲重跑隐藏 flaky；
- 记录 seed、trace/video、logs；
- 时间用 controllable clock；
- 并发用 barrier；
- 每个 flaky issue 有 owner/期限；
- 隔离测试只能短期且 release 前清零主演示 flaky。

## 8. 发布证据

- JUnit/Test reports；
- coverage/mutation summary；
- architecture report；
- Playwright HTML/trace；
- concurrency JSON/report；
- performance report；
- scan/SBOM；
- screenshots tied to release commit。
