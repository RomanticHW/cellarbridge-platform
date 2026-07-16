# 聚合目录

## 1. 设计原则

聚合是事务一致性和业务不变量边界，不是把数据库表组合成大对象。跨聚合规则通过 ID、快照、领域服务和最终一致事件完成。

| 模块 | 聚合根 | 主要实体/值对象 | 事务边界 |
|---|---|---|---|
| identity-access | Tenant | TenantStatus, SecurityPolicy | 启停租户 |
| identity-access | UserAccess | RoleBinding, PermissionSet | 修改本地访问映射 |
| partner | Partner | Contact, Address, RouteEligibility, PaymentTerm, CreditProfile, ReviewDecision | 客户草稿/提交/审核/停用 |
| catalog | WineProduct | ProducerRef, RegionRef, ContentTag | 酒款内容维护 |
| catalog | Sku | Vintage, PackageSpec, Volume | SKU 激活/停用 |
| inventory | SupplyPool | WarehouseRef, SupplyType, ServiceRegion | 供给池配置 |
| inventory | InventoryLot | StockQuantity, LotCode, ReceivedAt | 在手数量调整 |
| inventory | InventoryReservation | ReservationLine, LotAllocation | 全量预占/释放/消费 |
| quotation | Quotation | QuotationRevision, QuotationLine, PriceSnapshot, SupplyDecisionSnapshot 副本, ApprovalDecision, Acceptance | 一个报价业务编号及修订生命周期 |
| trade-planning | RoutePolicy | ConstraintDefinition, ScoreWeights | 发布策略版本 |
| trade-planning | RouteEvaluation | CandidateResult, RejectionReason, ScoreBreakdown | 保存一次评估 |
| trade-order | TradeOrder | OrderLine, CustomerSnapshot, RouteSnapshot, Cancellation | 订单状态和商业快照 |
| fulfillment | FulfillmentTemplate | StepDefinition, DependencyDefinition | 发布模板版本 |
| fulfillment | FulfillmentPlan | FulfillmentStep, Milestone, AdapterAttempt | 一个订单履约计划 |
| exception-center | ExceptionCase | Assignment, Note, RecoveryAttempt | 异常生命周期 |
| settlement | Receivable | PaymentRecord, PaymentReversal | 应收余额和付款链 |
| audit-reporting | ProjectionCheckpoint | ConsumerPosition | 投影处理进度 |

## 2. 为什么 QuotationRevision 不单独成为聚合

一个报价业务编号下的当前可编辑修订、审批和客户决定需要保持以下原子规则：

- 不能同时存在两个可编辑当前修订；
- 提交后修订冻结；
- 客户只能接受已发送的当前有效修订；
- 接受后报价业务对象终结；
- 新修订必须从退回状态显式创建。

P1 将其放在 `Quotation` 聚合内，控制修订行数上限（默认 50）和历史修订摘要加载。若历史版本很大，可将旧修订作为只读快照表延迟加载，不改变一致性语义。

Task 07C Quotation 分支为当前修订增加 `UNDECIDED`、`FROZEN`、`LEGACY_REEVALUATION_REQUIRED`；`FROZEN` 是路线绑定分配约束，不是库存预占。

## 3. 为什么 InventoryLot 与 Reservation 分开

库存批次拥有单批次数量不变量；预占单拥有“一个订单全成全败”和分配组合。实现时在一个数据库事务内通过明确 SQL 更新多个批次并创建 Reservation 聚合。领域模型不要求把所有批次加载到一个大聚合对象。

这是一个有意识的关系数据库一致性设计：

- `InventoryReservation` 负责命令幂等、订单行覆盖和状态；
- `InventoryLot` 数量通过原子条件 SQL保护；
- 应用服务以稳定次序协调；
- 数据库事务是全量原子边界；
- 测试验证聚合/SQL 协同不变量。

## 4. 快照值对象

跨上下文交易必须保存快照：

- `PartnerCommercialSnapshot`；
- `SkuSnapshot`；
- `PriceSnapshot`；
- `RouteDecisionSnapshot`；
- `PaymentTermsSnapshot`；
- `AddressSnapshot`。

快照包括源 ID、源版本、展示字段、决策必要字段和采集时间；不复制不必要敏感数据。

## 5. 标识

所有聚合根使用类型安全 UUID 值对象，例如 `QuotationId`、`OrderId`。人可读业务编号另行生成：

- `PAR-202607-000001`；
- `QUO-202607-000001`；
- `ORD-202607-000001`；
- `RES-202607-000001`；
- `FUL-202607-000001`；
- `EXC-202607-000001`；
- `REC-202607-000001`。

业务编号不可更改，不作为数据库主键，不依赖单机内存计数器。
