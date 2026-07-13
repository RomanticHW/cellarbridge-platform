# 产品验收标准

## 1. 验收方法

每个关键场景使用 Given/When/Then 表达，并映射到自动化测试层级。验收通过需要代码、测试、契约和可观察证据一致。

## 2. 客户与权限

### AC-PAR-001 激活客户后允许报价

- Given 客户处于 `PENDING_REVIEW`，审核人拥有权限且不是提交人；
- When 审核人批准指定贸易路径和付款条款；
- Then 客户状态为 `ACTIVE`，发布一次激活事件，销售可创建报价，审计记录包含双方身份。

### AC-SEC-001 租户隔离

- Given 租户 A 和租户 B 各有一个客户和订单；
- When A 的用户使用 B 的资源 ID 请求详情、列表筛选或导出；
- Then 返回 404 或受策略定义的拒绝，不泄露对象存在性、字段或计数。

## 3. 报价与路径

### AC-TRD-001 路径拒绝可解释

- Given 客户未获得宁波路径资格；
- When 运行路径评估；
- Then 宁波路径仍出现在结果中，状态为 rejected，reason code 为 `PARTNER_NOT_ELIGIBLE`，并包含安全可展示的解释和策略版本。

### AC-TRD-002 评分确定性

- Given 完全相同的输入快照和策略版本；
- When 在不同时间和不同进程执行评估；
- Then 各子分、总分、排序和推荐相同。

### AC-QUO-001 审批触发

- Given 默认折扣上限为 8%；
- When 销售提交 9% 折扣报价；
- Then 报价进入 `PENDING_APPROVAL`，审批项包含实际值、阈值和规则 ID。

### AC-QUO-002 报价过期边界

- Given 报价有效期为 `2026-08-01T00:00:00Z`；
- When 接受命令发生在截止时间前 1 毫秒；
- Then 接受成功；
- When 发生在截止时间或之后；
- Then 返回 `QUOTE_EXPIRED`，不创建接受事实。

### AC-QUO-003 客户字段安全

- Given 内部报价包含成本、毛利和路径评分；
- When 客户访问公开报价；
- Then 响应 schema 不包含这些字段，即使值为空也不返回。

## 4. 幂等和订单

### AC-ORD-001 重复接受不重复下单

- Given 一个已批准且有效的报价；
- When 同一幂等键并发提交 20 次接受；
- Then 所有成功响应引用同一接受记录；数据库只有一个接受事实和一个订单。

### AC-ORD-002 事件重复消费

- Given `QuotationAcceptedV1` 已成功创建订单；
- When 同一 event ID 被消费 10 次；
- Then 订单数不变，Inbox 记录一个处理结果，重复计数可观测。

## 5. 库存

### AC-INV-001 不超卖

- Given 某批次在手 100、已预占 0；
- When 100 个并发事务各尝试预占 2；
- Then 成功总量不超过 100，失败返回库存不足或并发重试耗尽，最终 `reserved <= on_hand`。

### AC-INV-002 全量回滚

- Given 订单有两行，第一行足量、第二行不足；
- When 执行预占；
- Then 两行都没有保留新预占，失败结果包含第二行缺口。

### AC-INV-003 稳定批次分配

- Given 多个等价批次；
- When 清空后以相同输入重新运行；
- Then 根据到货/批次/ID 稳定顺序得到同一分配。

## 6. 履约和异常

### AC-FUL-001 依赖阻止提前执行

- Given步骤 B 依赖步骤 A；
- When A 未完成时尝试开始 B；
- Then 返回 `FULFILLMENT_DEPENDENCY_NOT_MET`，状态不变。

### AC-EXC-001 失败异常去重

- Given 同一步骤失败事件重复投递；
- When 异常模块处理；
- Then 只存在一个开放异常，其重复检测计数增加。

### AC-EXC-002 恢复重试

- Given 一个适配器失败异常；
- When 操作员选择重试且第一次仍失败、第二次成功；
- Then 每次尝试有独立记录，异常仅在源步骤达到恢复状态后才能关闭。

## 7. 应收

### AC-SET-001 部分付款

- Given 应收 10,000.00 CNY；
- When 登记 4,000.00；
- Then 状态为 `PARTIALLY_PAID`，余额 6,000.00；
- When 再登记 6,000.00；
- Then 状态为 `PAID`，余额 0。

### AC-SET-002 付款冲正

- Given 已登记付款 4,000.00；
- When 财务以原因冲正；
- Then原付款不删除，新增 -4,000.00 的关联冲正，余额恢复。

## 8. 架构与工程验收

### AC-ARC-001 模块边界

- Given 任一模块实现；
- When 运行架构测试；
- Then 不存在对其他模块 `internal` 包、表或 Repository 的引用。

### AC-EVT-001 崩溃恢复

- Given 订单事务已提交但事件尚未对外发布；
- When 进程终止并重启；
- Then 待发布记录被重新处理，消费者幂等，最终创建一次后续工作。

### AC-DEMO-001 一键启动

- Given 干净开发机已安装受支持 Docker、Java 和 Node；
- When 按 README 执行 core profile 启动命令；
- Then 健康检查通过、可登录、种子数据可见、主演示前五幕可完成。

## 9. 证据映射

每条 AC 在实现阶段必须映射至少一个测试或演示步骤，记录在 `docs/05-delivery/10-implementation-status.md`。没有证据的条目不得标记 Done。
