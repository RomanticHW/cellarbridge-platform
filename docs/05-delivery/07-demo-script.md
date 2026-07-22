# 10 分钟人工演示脚本

目标观众是 Java 后端、全栈或架构岗位的技术评审者。所有屏幕使用本地合成数据；不要展示
登录密码、customer capability URL、浏览器账号、终端本地路径或未脱敏日志。

## 准备

```bash
make demo-reset
```

确认命令输出 Flyway V22、应用地址和 demo-only 角色。打开 `/app`，预先准备 Sales、Manager、
Administrator、Finance 四个无痕窗口。完整自动复验命令为 `make demo-e2e`。

## 00:00–01:00 — 问题与架构

- 打开 README 的核心流程和模块化单体图。
- 说明这不是普通商城或 CRUD 模板：重点是客户资格、可解释路径、重复下单、库存并发、
  长流程失败和商业字段边界。
- 讲清取舍：本地强一致 + PostgreSQL publication/Inbox；未部署 Kafka/Redis。

预期屏幕：README 架构与 Available 能力表。

## 01:00–03:00 — 客户、SKU、报价与路径

- Sales 创建合成客户并提交；Manager 独立激活。
- Sales 搜索 `Moonlit Terrace`，创建报价，比较 route reason/score。
- 9% 折扣触发审批；Manager 复核 rule IDs 后批准并签发。

预期屏幕：客户 ACTIVE、报价 PENDING APPROVAL、route comparison、ISSUED。
技术要点：tenant predicate、提交人与审批人分离、版本化定价/路径策略、不可变 Revision。

## 03:00–04:15 — 客户接受与唯一订单

- 打开客户安全页面，指出没有成本、毛利、Pool、Lot 或内部 ID。
- 接受报价，刷新回执并进入受保护订单。
- 说明并发/重放由 acceptance、Inbox 和 quotation unique constraint 共同约束。

预期屏幕：Quotation accepted、唯一 `ORD-*`、订单 `RESERVED`。
技术要点：capability token 不进入日志，重复 delivery 不产生第二个订单。

## 04:15–05:30 — 库存正确性

- Warehouse 查看 Reservation 和 allocation；指出单位、Pool、Lot 只向精确库存权限开放。
- 对照 `JdbcAtomicInventoryLotRepository` 的条件更新和并发测试。

预期屏幕：CONFIRMED Reservation 与 allocation 守恒。
技术要点：确定顺序、savepoint、`reserved <= onHand`，不依赖 JVM/Redis lock。

## 05:30–07:30 — 履约失败与异常恢复

- Administrator 打开 Fulfillment plan，依序执行节点。
- 在第二节点选择 `Timeout`，进入自动创建的 Exception。
- Acknowledge → investigate → retry recovery → close，再返回计划完成节点。

预期屏幕：`SIMULATED_ADAPTER_TIMEOUT`、Exception CLOSED、Fulfillment COMPLETED。
技术要点：故障开关只在 demo profile；恢复前读取源状态；重复 retry 不产生第二个 adapter attempt。

## 07:30–08:45 — 应收、付款与冲正

- Finance 查看 Fulfillment 触发的唯一应收。
- 记录部分付款、补齐余额，再对其中一笔做部分冲正。

预期屏幕：OPEN → PARTIALLY PAID → PAID → PARTIALLY PAID 与 append-only payment/reversal。
技术要点：金额 `numeric(19,4)`、幂等键、乐观版本、仅记录外部付款事实，不宣称真实网关。

## 08:45–10:00 — 看板、审计与工程证据

- Manager 打开 dashboard 和 audit，指出 `dataAsOf`、projection status、业务 correlation。
- 快速展示 Modulith/ArchUnit、OpenAPI/AsyncAPI、Testcontainers、trace walkthrough、SBOM 与
  performance/resilience report。

预期屏幕：非零业务指标、审计表、统一时间线。
技术要点：报表最终一致；OTel 不参与业务正确性；证据与 tag commit/checksum 绑定。

## 失败 fallback

- 若 Docker 资源不足，使用 v1.0.0 Release 中由精确 tag 旅程生成的六张截图；明确说明不是 live。
- 若 full profile 不可用，继续使用 core；可观测证据使用已提交 dashboard/trace walkthrough。
- 若浏览器中断，使用 OpenAPI、真实 PostgreSQL 测试和 release workflow 结果；不伪造操作结果。

每个问题按“业务风险 → 备选 → 决策 → 不变量/代价 → 测试证据 → 演进条件”回答。
