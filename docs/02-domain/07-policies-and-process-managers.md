# 领域策略与流程管理器

## 1. 策略与流程的区分

- **领域策略（Policy）**：对输入做确定性业务判断，无长期状态；
- **流程管理器（Process Manager）**：跨聚合/模块跟踪长流程，响应事件并发命令；
- **应用服务**：组织一次用例和事务；
- **调度器**：在时间条件满足时发起幂等命令。

不得把所有逻辑放入名为 `Manager` 或 `Service` 的通用类。

## 2. PriceCalculationPolicy

输入：价格表快照、客户等级、数量、折扣、费用、币种、舍入版本。

输出：每行和总计的不可变 `PriceSnapshot`。

规则：

- `BigDecimal` 和币种精度；
- 同一策略版本确定性；
- 费用分摊采用稳定算法，最后一行吸收舍入差；
- 预计成本/毛利与客户价格字段分离；
- 不实现真实税法。

## 3. QuotationApprovalPolicy

输入：报价快照、客户条款、销售权限、路径选择。

输出：`ApprovalRequirement` 列表或自动批准。

默认规则：

- 折扣 > 8%；
- 预计毛利 < 15%；
- 账期超过客户默认；
- 人工价格；
- 选择非推荐路径；
- 有效期超过 30 天。

阈值为演示配置和版本，不代表研究对象真实规则。

## 4. TradeRouteEvaluationPolicy

### 阶段一：硬约束

对每条 route 评估资格、区域、供给类型、MOQ、交付日期、币种/条款兼容性。拒绝结果包含规则 ID 和参数，但不能泄露内部成本。

### 阶段二：评分

标准化子分：

- CostScore：候选总费用相对值；
- LeadTimeScore：预计天数相对值；
- SupplyConfidenceScore：供给类型、更新时间、覆盖率；
- OperationalSimplicityScore：步骤和人工依赖相对值。

默认权重 40/30/20/10。策略校验权重和为 1。

### 阶段三：稳定推荐

总分降序；同分按 route priority、route code。保存全部候选，不只保存赢家。

## 5. InventoryAllocationPolicy

P1：

1. 只考虑与选定路径和供给池兼容的在手批次；
2. 排除过期/冻结/不可用批次；
3. 按预计可用日期、收货时间、批次代码、UUID 稳定排序；
4. 尽量使用较少批次，但不引入复杂优化；
5. 生成 allocation proposal；
6. 通过原子 SQL 实际提交，proposal 不是承诺。

## 6. FulfillmentTemplateSelectionPolicy

根据 route code、目的区域和模板生效版本选择唯一模板。无模板时订单进入可恢复异常，不临时创建空步骤。

## 7. QuotationToOrderProcessManager

响应 `QuotationAcceptedV1`：

1. Inbox 去重；
2. 调用订单创建用例，使用 quote ID 幂等；
3. 已存在则校验快照 hash 一致；不一致视为严重契约错误；
4. 成功后由订单可靠发布事件；
5. 通知 Quotation 链接订单；
6. 失败可重试，达到阈值进入系统操作异常。

该流程不跨模块开启分布式事务。

## 8. OrderReservationProcessManager

响应 `TradeOrderCreatedV1`：

- 对可自动预占供给发起 `ReserveInventoryForOrder`；
- 对未来/人工确认供给创建人工工作项，不伪装预占成功；
- 响应 ReservationConfirmed/Failed 更新订单状态；
- 重复事件幂等；
- 失败重试有上限和指标。

## 9. FulfillmentProcessManager

响应预占成功创建计划；步骤完成后计算后继步骤是否 Ready；失败/逾期发布异常事实；完成全部必需步骤发布 `FulfillmentCompletedV1`。

## 10. CancellationProcessManager

取消是 saga-like 流程：

1. Order 接受取消请求并进入 `CANCELLATION_PENDING`；
2. 请求 Fulfillment 停止/补偿允许步骤；
3. 请求 Inventory 释放未消费预占；
4. Settlement 检查是否需要冲正/阻止；
5. 全部必要确认后 Order 进入 CANCELLED；
6. 任一失败进入异常中心。

P1 可限制取消窗口，避免实现不可证明的复杂补偿。

## 11. ReceivableProcessManager

由配置里程碑（订单创建、发货或履约完成）触发唯一应收；事件重复不重复创建。具体触发点固定在演示配置并在订单快照记录。

## 12. 时间驱动策略

调度命令：

- `ExpireDueQuotations`；
- `MarkOverdueFulfillmentSteps`；
- `MarkOverdueReceivables`；
- `RetryDueEventPublications`；
- `ReconcileStuckProcesses`。

每个调度命令具备：窗口、游标、幂等键、批次限制、锁/抢占策略和指标；重复运行安全。
