# 业务调查源事实合同
## 范围与协议
当前只交付 Quotation、Trade Order、Reservation、Fulfillment、Exception、Receivable 六个 source-owned facade，用于解释路线、订单卡点、预留、异常影响和应收逾期；不增加 MCP Tool/Resource/Prompt、case graph、cursor 或 discovery。后续保留 Tool `cellarbridge_investigate_business_case`、`cellarbridge_explain_route_decision`，以及 `cellarbridge://{quotation|trade-order|reservation|fulfillment|exception|receivable}/{id}` 和 `cellarbridge://case/{subjectType}/{subjectId}`；当前 runtime 不得宣称可用。
业务 ID 必须为 canonical UUID。隐藏、跨 tenant、错误 revision/relation 和不存在对象采用相同不可枚举语义，不泄露 type、ID、数量、位置或关系。后续 Envelope 2.0 direct source 固定 `projectionStatus=NOT_APPLICABLE`、`freshness.mode=OBSERVATION_AGE`；`dataAsOf` 为最新可见源时间，所有 projection watermark/count 为 `null`。稳定 warning 只有 `PERMISSION_TRIMMED`、`EVENTUAL_LINK_PENDING`、`UNTRUSTED_DATA_TRUNCATED`，均不得携带隐藏对象细节；错误沿用既有安全码和安全消息。
## DTO、查询与字段
| Facade 查询 | 最小事实与裁剪 |
|---|---|
| Quotation：ID + 可选 revision | id/number/revision/status/partner/currency/version/time；price、cost/margin、route charges/score/override、exact supply 分别授权；Buyer tenant+partner |
| Trade Order：ID / source quotation | id/number/status/downstream snapshot/version/time；source link、金额、route 和内部状态分别裁剪；Warehouse fail closed |
| Reservation：ID / order ID | id/order/status/failure/attempt/version/time；quantity/pool/lot/warehouse 需 `inventory:read` + exact + assignment |
| Fulfillment：ID / order / reservation / typed step | id/order/reservation/status/current step/version/time；Buyer 拒绝；Warehouse 同时需 reservation assignment 与 active warehouse task |
| Exception：ID / typed source + limit | id/category/severity/status/typed source/version/time；System 仅技术异常；source type 必须匹配 category |
| Receivable：ID / order + limit / fixed fulfillment trigger | id/order/status/currency/due/trigger/version/time；金额、payment reference/actor/reversal reason 分别授权；Buyer 无 payment |
参数只允许业务 ID、固定 enum 和 `1..20` limit，不接受 tenant、actor、role、permission、partner、warehouse、token、include/expand、SQL、URL 或 predicate。所有查询只读且只查所属 schema，不改变 REST/OpenAPI、AsyncAPI、状态机、事件、写路径或 migration。
自由文本固定为 `{"classification":"UNTRUSTED_BUSINESS_DATA","value":"...","truncated":false}`；最多 2,000 Unicode code point，按 code point 截断。它不得进入 safe message、warning、Prompt、URI、日志、trace 或 metric。
## 授权矩阵与关系
Permission 先于角色且角色不补权：Sales Representative 仅本人 quotation/order；Manager 仅显式团队权限；Trade Operator 仅显式 order/fulfillment/exception；Warehouse 必须具备基础 read 和真实 assignment/task，exact 仍需提升权限；Finance 角色本身仅授 receivable 与必要 order 摘要，不推导 fulfillment/exception/route/inventory，跨域关系仍须显式 source read；Administrator 不绕过对象规则；Auditor 的 `audit:read` 不授 source read；Buyer 仅 OIDC partner 的 customer-safe quotation/order/receivable；System 仅技术 exception。权限或 assignment 撤销后下一次调用立即生效。
Warehouse Reservation 必须由 `reservation -> allocation -> supply_pool -> warehouse_assignment` 证明；无 allocation 或 assignment 时不可枚举。Warehouse Fulfillment 还必须存在当前 active warehouse-owned task。
后续只允许持久化 typed edge：quotation->order、order->reservation、order->fulfillment、reservation/fulfillment->exception、fulfillment->receivable、order->receivable。边的两端均须通过各自 authorizer；Exception JSON 仅按已知事件 schema 转固定 DTO，禁止通用 Map/JSON path、编号、时间或文本推断。
## 验收
六个 facade 覆盖成功、空结果、同 tenant 无权、跨 tenant、猜 UUID、撤权，以及 owner/manager/Buyer partner、历史 revision、Warehouse assigned/unassigned/no-allocation、exact、技术/业务异常、payment/reversal 字段矩阵。Modulith/ArchUnit 证明无跨模块 `internal`、跨 schema SQL、环或 platform 业务规则；现有六 Tool、三 Resource、三 Prompt、OIDC、限流、bulkhead、timeout 和 production security 必须回归通过。
