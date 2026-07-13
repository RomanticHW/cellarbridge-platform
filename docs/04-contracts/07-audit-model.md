# 审计模型

## 1. 目标

审计回答谁在何时、基于什么版本、执行了什么业务决定、结果如何，并关联后续事件。它不是完整请求日志，也不保存秘密。

## 2. AuditEntry

字段：

- id、tenantId；
- occurredAt；
- module、action、outcome；
- subjectType/id/number；
- actorType/id/displaySnapshot；
- commandId、correlationId、causationId、traceId；
- previousState/newState；
- reasonCode、safeReason；
- changedFields（字段名和安全摘要）；
- sourceEventId；
- classification；
- schemaVersion。

记录追加，不提供普通更新/删除 API。

## 3. 必审计动作

- 客户提交、批准、退回、停用；
- 报价提交、审批、路径覆盖、发送、接受/拒绝/过期；
- 订单创建/取消；
- 库存调整、预占、释放、消费；
- 履约人工完成、失败、跳过、恢复；
- 异常分派/关闭；
- 付款登记/冲正；
- 权限变更；
- event replay、demo reset、配置版本发布。

普通读不全量审计；敏感导出/查看可单独记录。

## 4. 业务审计与技术日志

| 业务审计 | 技术日志 |
|---|---|
| 长期、语义稳定 | 短期、诊断 |
| 人/业务动作 | 类/线程/错误 |
| 安全摘要 | 不保证完整业务历史 |
| 可按业务对象查询 | 按 trace 查询 |
| 不含堆栈 | 可含安全堆栈 |

二者通过 trace/correlation 关联，不能互相替代。

## 5. 变化摘要

- 不保存密码/token；
- 成本/毛利可记录“字段已改变”或权限加密摘要，不默认保存前后值；
- 地址/联系人记录字段名和掩码摘要；
- 报价金额可记录总额变化，因交易自身已保存快照；
- reason 文本有长度、敏感信息提示和审计分类。

## 6. 时间线投影

统一时间线消费各模块事件，生成：

- `INTERNAL` 视图；
- `CUSTOMER` 视图；
- `TECHNICAL` 视图。

同一源事实可映射不同安全描述。投影最终一致，显示 lastUpdated；事件重复按 eventId 去重。

## 7. 完整性

演示实现可使用：

- 数据库只追加约束；
- entry hash/前链 hash（作为技术增强，非法律不可篡改声明）；
- 定期完整性检查；
- 对高危动作双记录业务事实 + 审计投影。

不得把 hash 链宣传成区块链或满足特定法律认证。

## 8. 保留和访问

演示默认长期保留合成审计；生产保留需法律/业务决定。审计读取需 `audit:read` 和字段权限；导出受限、带水印/审计（P2）。
