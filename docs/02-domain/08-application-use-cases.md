# 应用层用例设计

## 1. 应用层职责

应用层：

- 验证当前主体权限和租户；
- 加载所需聚合或快照；
- 调用领域行为/策略；
- 定义本地事务；
- 保存聚合和可靠事件；
- 返回用例结果。

应用层不得：

- 复制聚合不变量；
- 返回 JPA Entity；
- 直接拼装其他模块内部表；
- 把远程/消息发送放入不可控的业务事务；
- 使用通用 `execute(Map)` 模糊契约。

## 2. 命令命名

命令使用业务动词：

```text
CreatePartnerDraft
SubmitPartnerForReview
ApprovePartner
CreateQuotation
EvaluateTradeRoutes
SubmitQuotation
ApproveQuotation
IssueQuotation
AcceptQuotation
CreateOrderFromAcceptedQuotation
ReserveInventoryForOrder
GenerateFulfillmentPlan
CompleteFulfillmentStep
OpenExceptionCase
RequestExceptionRecovery
RecordPayment
ReversePayment
```

每个命令包含：commandId、tenantId（由安全上下文绑定而非信任客户端）、actor、expectedVersion、业务参数、可选 idempotencyKey。

## 3. 查询命名

查询与命令分离，返回显式 read model：

- `SearchCatalogQuery`；
- `GetPartnerDetailQuery`；
- `GetQuotationDetailQuery`；
- `GetOrderTimelineQuery`；
- `ListWorkItemsQuery`；
- `GetDashboardQuery`。

查询必须在租户/权限谓词后分页，不把加载所有数据后内存过滤当安全措施。

## 4. 关键用例事务

### AcceptQuotationHandler

1. 建立安全上下文，客户采购身份必须关联 Partner；
2. 解析 idempotency key；
3. 加载 Quotation 并锁定/乐观版本；
4. 聚合检查 SENT、有效期、修订和客户；
5. `quotation.accept(...)`；
6. 保存接受事实和可靠事件；
7. 同事务写 idempotency result；
8. 返回接受 ID、报价状态和未来订单关联状态。

不在该事务同步创建订单；避免跨模块写入。

### CreateOrderFromAcceptedQuotationHandler

1. Inbox 去重；
2. 按 sourceQuotationId 查唯一订单；
3. 存在时校验 snapshot hash 并返回；
4. 不存在时从事件快照构造 TradeOrder；
5. 保存订单和可靠事件；
6. 唯一冲突时重查并返回已有；
7. 完成 Inbox。

### ReserveInventoryForOrderHandler

1. Inbox/命令幂等检查；
2. 验证订单供给允许自动预占；
3. 为订单行加载候选批次投影；
4. AllocationPolicy 生成稳定候选顺序；
5. 开启事务并逐 allocation 执行原子条件 SQL；
6. 任一失败抛出受控异常触发回滚；
7. 保存 Reservation、allocation 和事件；
8. 完成 Inbox；
9. 事务外由发布器发送。

### CompleteFulfillmentStepHandler

1. 权限和负责人校验；
2. 加载 Plan；
3. 检查 expectedVersion 和依赖；
4. 验证所需输入/附件元数据；
5. 完成步骤并产生里程碑；
6. 解锁后继步骤；
7. 保存计划和事件。

## 5. 返回模型

命令返回最小确定结果：

- 聚合 ID/业务编号；
- 新状态和版本；
- 允许的下一动作；
- 对幂等命令可返回 `replayed: true`；
- 不直接返回跨模块完整详情，UI 后续查询 read model。

## 6. 错误映射

应用层将领域错误映射为稳定错误码和 HTTP 语义：

- 400：格式/基础校验；
- 401/403：认证/授权；
- 404：租户范围内不存在；
- 409：状态冲突、重复资源、业务不变量；
- 412：If-Match/expectedVersion 不匹配；
- 422：语法正确但业务输入不可处理（谨慎使用）；
- 429：受控限流；
- 503：依赖暂不可用且安全重试。

## 7. 包结构示例

```text
com.example.cellarbridge.quotation
├── QuotationModuleApi.java
├── QuotationQueries.java
├── events/
└── internal/
    ├── application/
    │   ├── command/
    │   ├── query/
    │   └── process/
    ├── domain/
    │   ├── model/
    │   ├── policy/
    │   └── event/
    ├── infrastructure/
    │   ├── persistence/
    │   └── messaging/
    └── interfaces/
        └── rest/
```

公开模块 API 不泄露 internal 类型。
