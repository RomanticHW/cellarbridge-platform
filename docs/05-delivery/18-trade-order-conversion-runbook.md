# 报价转订单运行手册

Status: **Available in Task 07**

Requirements: **UC-ORD-001, FR-ORD-001–004**

Contract release: **OpenAPI 1.4.0**

## 1. 可观察行为

- 每个 `QuotationAcceptedV1` 携带创建订单所需的报价、客户、行项目、金额、账期、路线、交付地址和接受事实快照。Trade Order 不回查 Quotation 私有表来重建商业条件。
- 首次成功消费创建一个不可变商业快照，初始状态固定为 `PENDING_RESERVATION`，并在同一事务内保存 Inbox `PROCESSED` 和待外部发布的 `TradeOrderCreatedV1`。
- `(tenant_id, source_quotation_id)`、来源事件和订单号数据库唯一约束是最终并发防线。相同事件或相同报价/相同快照的重放返回既有订单；相同报价但不同快照是不可覆盖的终态冲突。
- Quotation 作为 `TradeOrderCreatedV1` 的独立消费者，在自己的事务中写入不可变订单链接并执行 `ACCEPTED → CONVERTED`。该步骤失败时订单不回滚、报价保持 `ACCEPTED`，后续重试最终完成链接。
- Portal 在接受后轮询自己的客户安全报价资源；订单链接出现后只展示安全订单号和进入受 OIDC 保护订单页面的链接。Portal capability 不能授权订单 API。
- `/api/v1/orders*` 是内部资源；Partner-scoped Buyer 被明确拒绝。`/api/v1/buyer/orders*` 从已认证身份的 Partner mapping 取 scope，并在查询和分页之前同时约束 tenant 与 Partner，不接受调用方提交 Partner scope。

## 2. 交付语义与事务边界

系统声明的是 **at-least-once delivery + at-most-one business effect**，不声明端到端 exactly-once。

1. Quotation 接受事务原子保存客户决定和 `QuotationAcceptedV1` publication。
2. 本地 dispatcher 按 consumer 读取可处理 publication。
3. `tradeorder.quotation-accepted.v1` 在一个本地事务内领取 Inbox、创建订单/行项目/时间线、写 `TradeOrderCreatedV1` publication 并完成 Inbox。
4. `quotation.trade-order-created.v1` 在另一个本地事务内领取自己的 Inbox、写订单链接、调用聚合的 `convert` 转换状态并完成 Inbox。
5. 任一 handler 事务回滚后，独立失败记录事务把安全错误码和下次重试时间写入 Inbox；业务写入不会以半完成状态提交。

每个 consumer 对同一 event ID 只有一个 Inbox 业务效果；同一 event ID 若跨 tenant、事件类型或 payload hash 重用，会作为 binding conflict 终止，不能被当成正常重复。Inbox 不跨模块写业务表，业务模块也不直接更新彼此的 schema。

## 3. 有界重试与失败可见性

- 默认最多尝试 5 次，基础退避 5 秒，指数增长并封顶 300 秒；批大小默认 50。配置入口是 `cellarbridge.platform.local-events.*`。
- 存储等暂态失败进入 `FAILED_RETRYABLE`；达到上限后进入 `FAILED_FINAL`。schema/version、subject/payload、来源快照和 tenant binding 冲突直接进入 `FAILED_FINAL`。
- 最终失败保留 consumer、event ID、尝试次数和安全错误码，禁止无限快速重试；日志只记录标识符和错误类型，不记录 capability token 或完整商业 payload。
- local event dispatcher 默认关闭，只在明确的 demo/test profile 启用。生产部署必须显式选择本地 dispatcher 或外部 broker adapter，不能同时对同一 consumer 无约束投递。

`platform_event.event_publication.status = PENDING` 仍表示**外部发布尚未完成**。本地消费成功只写对应 consumer Inbox，不把 publication 改成 `PUBLISHED`，因此不会把模块内处理误当成 Kafka/外部 broker acknowledgement。

## 4. 数据、边界与安全字段

- `trade_order` schema 只通过逻辑 ID 引用 Quotation、Partner、Catalog、Inventory 和 IAM；不存在跨模块外键。订单行与时间线只使用 Trade Order 自己 schema 内的外键。
- 商业快照、订单行和创建时间线不可变；consumer 在查询数据库前校验 `QuotationAcceptedV1` 并按共享的 Snapshot Hash V1 projection 重算，格式错误终止为 `QUOTATION_ACCEPTED_EVENT_INVALID`，合法 payload 的 hash 不一致终止为 `QUOTATION_ACCEPTED_SNAPSHOT_HASH_MISMATCH`。
- V9 允许历史 `QuotationAcceptedV1` 缺少新增的可选 `sourceOwnerId`：新订单仍保留完整商业快照，但 owner 为 null 的记录不会进入 Sales Representative 的本人范围，只有具有全租户读取范围的内部角色可见。当前生产者始终写入 owner。
- Quotation 消费历史 `TradeOrderCreatedV1` 时允许缺少可选 `acceptanceId`；它使用自己持久化的接受事实补齐不可变链接，若事件显式携带该字段则必须与权威接受记录一致。
- `north.buyer` 的 nullable identity mapping 绑定到合成 active Partner。`partnerId` 由可信身份映射进入 `TenantContext` 和 `/me`；普通内部用户保持 null。映射列不建立跨 schema 外键。
- 普通内部订单 DTO 不返回 source event、acceptance、correlation 或 causation ID；这些技术标识继续持久化，留给受限的系统操作/审计能力使用。
- Buyer DTO 是独立 closed allow-list，只包含客户、公开行项目、公开价格、交付地址、公开路线日期、流程占位和客户可见时间线。它不返回成本、毛利、内部路线分数、供应池/仓库/批次、snapshot hash、correlation/causation、内部备注或内部事件证据。
- 不属于当前 tenant 或映射 Partner 的订单统一表现为 `404`；缺失 Partner mapping 或 Buyer 调用内部资源返回 `403`，避免利用响应区分其他 scope 的资源。

## 5. OpenAPI 1.4.0 契约决策

此前 1.3.0 中 Order response 仍是未实现的设计契约，部分嵌套对象开放且复用了无法由冻结接受事件可靠构造的报价行结构。本切片将其收敛为显式的 `OrderLineSnapshot`、closed commercial/timeline/process schemas，并拆分内部与 Buyer DTO；同时为 portal 回执和 `/me` 增加可选订单链接/nullable Partner scope。

这是 API contract 的显式 minor release **1.4.0**：已实现的既有 quotation/portal 行为保持兼容；Task 07A 同时完成 pre-1.0 v1 specification correction，收紧三个订单必需快照字段并统一裸小写 64 位 snapshot hash，`sourceOwnerId` 继续可选。

## 6. 验证与操作

```bash
make validate
make test
make order-e2e
```

`make order-e2e` 在隔离 Compose project 中构建 PostgreSQL、Keycloak、backend 和 frontend，执行真实 portal 接受 → eventual order → Buyer OIDC 安全详情链路，并验证错误 tenant/Partner 不能读取该订单。专用 Playwright 配置关闭 screenshot 与 trace；脚本在输出和服务日志两侧防止 capability URL 泄漏，结束后销毁 volumes。

重点自动证据包括：

- Trade Order 聚合合法状态转换、初始 `PENDING_RESERVATION` 和不可变快照；
- 同 event、不同 event/同 quotation、同 event ID 跨 tenant 或变更 payload 的重复/并发矩阵；
- Inbox 与业务副作用同事务、handler 失败回滚、独立失败记录、有界退避和 final failure；
- `TradeOrderCreatedV1` 可靠 publication 以及 Quotation eventual `CONVERTED`；
- internal/Buyer tenant、Partner、owner 与字段 allow-list API 矩阵；
- React order list/detail 的 loading、empty、error、forbidden、polling 和安全渲染；
- `trade-order-conversion.yml` 作为 PR Required quality gate 的独立真实 E2E。

## 7. 已知限制与下一步

- 本运行手册聚焦转单边界；当前完整 v1.0.0 会继续消费 `TradeOrderCreatedV1`，由 Inventory 编排 all-or-nothing 预占并把结果应用回订单，详见 Inventory、Fulfillment 与 Exception 运行手册。
- `PENDING_RESERVATION` 仍是真实中间态，不代表预占、履约或应收已经完成；各下游模块只拥有自身事实，不由 Trade Order 直接写表。
- 外部 Kafka adapter 与外部 publication acknowledgement 不在 core demo 的当前执行路径；保留的 `PENDING` publication 是后续 adapter 的可靠输入，不是丢失事件。
