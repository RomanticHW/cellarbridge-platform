# 报表与读模型

## 1. 原则

报表消费业务事件建立投影，不跨模块写模型实时大联表。读模型可删除重建，源业务不可依赖其做交易决策。

## 2. 读模型

### Order Timeline

按 order/quote/correlation 聚合：报价批准、客户接受、订单创建、预占、计划、步骤、异常、付款。存内部和客户安全摘要。

### Quotation Funnel

按日/团队/owner：draft、submitted、approved、issued、accepted、expired、converted；计算转化和周期。

### Route Decision

候选有效/拒绝、拒绝原因、推荐、选择、覆盖、策略版本。用于解释与指标。

### Inventory Reservation Metrics

请求/成功/失败、SKU/供给类型、冲突、缺口、耗时。不能暴露其他客户订单。

### Fulfillment SLA

计划、步骤、计划/实际时间、逾期、异常和恢复。

### Receivable Summary

open/partial/paid/overdue、余额和 aging bucket（合成财务演示）。

### Work Queue Projection

角色/负责人、类型、到期、状态、业务深链。

## 3. 投影处理

- Inbox 去重；
- 同一 subject version 防旧事件覆盖新状态；
- checkpoint；
- 批量重建；
- 投影 schema 版本；
- 重建期间可返回 stale 标记或临时不可用；
- 指标记录 projection lag。

## 4. 一致性说明

页面必须区分：

- source-of-truth detail（模块 API）；
- eventually consistent summary/dashboard；
- lastUpdated/projectionLag；
- 点击指标进入列表时，短暂计数差异可解释。

## 5. 查询

- 预聚合日/小时 bucket；
- tenant 第一索引；
- 时间范围最大限制；
- high-cardinality 维度不无限开放；
- 查询参数白名单；
- 大导出异步 P2。

## 6. 重建

```text
pause projection consumer
create new projection version tables
replay events/checkpoint
validate counts/hash samples
switch read alias/view
resume incremental
retain old briefly then drop via migration
```

P1 可使用简化清空重建，必须只操作 audit_reporting schema。

当前 V21 实现采用 generation 切换：增量读取只查询 `ACTIVE` generation，重建把同租户可靠
publication 按 `(occurredAt,eventId)` 重放到 `STAGING`，验证后在事务中将旧 generation 标为
`RETIRED` 并激活新 generation。重建期间旧读模型继续服务，不 truncate 源业务或 active 投影。

乱序状态更新按 business version、occurredAt、eventId 的稳定顺序比较；缺少先决 OPEN 的关闭事件
进入 projector inbox `PENDING`，先决事件到达后同事务协调完成。相同 eventId/payload 无害去重，
不同 tenant/type/payload 的绑定冲突拒绝覆盖可信结果。

Dashboard 的日期按 UTC 边界解释，最大 367 天。API 返回 `dataAsOf`、`projectionLagSeconds` 和
`CURRENT/STALE/REBUILDING/EMPTY`，前端原样展示，不把最终一致读模型表示为交易真相。

## 7. 验收

- 重复事件不重复计数；
- 乱序事件不倒退状态；
- 清空后重建关键指标相同；
- 双租户隔离；
- 客户视图不含内部字段；
- lag 指标可见。

实现证据位于 `AuditReportingIntegrationTest`、`ReportingWorkspace.test.tsx` 和
`audit-reporting.live.spec.ts`；代表 SQL 使用 tenant-first index 的 `EXPLAIN` 断言。
