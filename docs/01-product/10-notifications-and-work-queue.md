# 通知与工作队列设计

## 1. 定位

通知不是业务事实的唯一载体。业务对象、工作项和审计记录是事实；通知只是提醒。用户错过通知不能导致业务状态丢失。

P1 提供站内通知和统一工作队列，邮件/短信只使用模拟适配器，不连接真实服务。

## 2. 工作项模型

`WorkItem` 最小字段：

- `work_item_id`、`tenant_id`；
- `type`：`PARTNER_REVIEW`、`QUOTATION_APPROVAL`、`FULFILLMENT_STEP`、`EXCEPTION_ACTION`、`RECEIVABLE_FOLLOW_UP`；
- `subject_type`、`subject_id`、`subject_number`；
- `title`、安全摘要；
- `priority`：LOW/NORMAL/HIGH/CRITICAL；
- `status`：OPEN/CLAIMED/COMPLETED/CANCELLED/EXPIRED；
- `candidate_role`、可选 `assignee_user_id`；
- `due_at`、`created_at`、`completed_at`；
- `source_event_id` 唯一约束，用于事件幂等；
- `version` 用于并发领取。

工作项不复制完整业务规则。执行动作时必须调用源模块的公开命令，源模块重新授权和校验当前状态。

## 3. 创建和关闭

| 业务事实 | 工作项 | 自动关闭条件 |
|---|---|---|
| PartnerSubmittedForReviewV1 | 客户审核 | 客户批准/退回/拒绝 |
| QuotationApprovalRequestedV1 | 报价审批 | 审批终态或报价撤销 |
| FulfillmentStepReadyV1 | 履约步骤 | 步骤完成/取消/被替换 |
| ExceptionOpenedV1 | 异常处理 | 异常关闭 |
| ReceivableOverdueV1 | 应收跟进 | 付清/冲销/豁免（P2） |

重复事件不得重复创建工作项；乱序关闭事件在工作项创建后应可重放/对账。

## 4. 通知模型

通知渠道：

- `IN_APP`：P1 实现；
- `EMAIL_SIMULATOR`：P1 将渲染结果写入开发收件箱或日志安全摘要；
- `WEBHOOK_SIMULATOR`：可选，用于展示签名和重试，不默认启用；
- 真实邮件、短信、企业 IM：P2/非范围。

通知模板必须：

- 使用模板 ID 和版本；
- 不在事件中传递敏感完整对象；
- 根据接收者权限构造内容；
- 提供业务编号和受控深链接；
- 支持去重键和发送状态；
- 不把客户门户 token 写入日志。

## 5. 用户交互

- 顶部工作项计数允许短暂延迟；
- 列表按到期时间和优先级排序；
- 领取采用乐观并发，失败时刷新当前负责人；
- “完成工作项”不能绕过源业务动作；
- 用户可标记站内通知已读，但不能删除审计事实；
- 系统操作员能看到失败通知和重试，不得看到被屏蔽的敏感正文。

## 6. SLA 和调度

每个工作项类型可以配置默认 SLA。调度任务按窗口扫描：

- 即将到期：发布提醒；
- 已逾期：提高优先级并发布逾期事实；
- 重复扫描使用 `(work_item_id, escalation_level)` 唯一键；
- 使用数据库时间/注入 Clock，避免多实例重复副作用；
- 扫描游标和延迟指标可观测。

## 7. 权限

- 只能读取本租户工作项；
- 用户可读本人、其候选角色或被授权全局查看的工作项；
- 领取要求候选角色；
- 转派要求 `work-item:reassign`；
- 系统操作员仅处理技术失败，不自动获得商业数据字段权限。

## 8. 验收重点

- 同一事件重复 10 次只创建一个工作项；
- 两人并发领取只有一个成功；
- 源业务已终态时，旧工作项自动取消或完成；
- 通知失败不回滚业务事务；
- 通知重试不重复产生业务动作；
- 日志和开发收件箱不泄露 token、成本、毛利或其他租户数据。
