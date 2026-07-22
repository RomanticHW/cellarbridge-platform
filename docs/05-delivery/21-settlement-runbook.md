# 应收与付款运行手册

## 1. 当前边界

Task 11 完成从履约结束到外部付款事实登记的演示闭环。系统不发起真实收单或银行转账，
不生成发票、会计分录或税务结果，也不做多法律实体合并。所有演示数据为合成数据。

当前结算纵向切片为 **Available**。

## 2. 应收创建

- Settlement 消费 `TradeOrderCreatedV1`，保存客户、订单、币种、金额和付款条款的不可变模块内快照；
  不读取 Trade Order 内部表。
- `receivable_trigger_policy` 保存 code、version、trigger type 与 active 状态。当前策略
  `DEMO-FULFILLMENT-COMPLETED` v1 在 `FulfillmentCompletedV1` 后创建应收。
- 业务身份 `(tenant, order, policy code/version)` 与事件触发身份 `(tenant, trigger type/id)`
  都由唯一约束保护；重复事件返回同一应收，不重复发布 `ReceivableCreatedV1`。
- 零金额订单保存快照但不创建应收；due date 由履约完成 UTC 日期加冻结的 payment term days 计算。

## 3. 付款、冲正与状态

- 金额统一为 `numeric(19,4)`，付款必须为正数且币种与应收一致，不能超过 outstanding。
- Payment 是不可编辑、不可删除的外部事实。`(tenant, external_reference)` 与请求幂等键共同保护重试；
  相同 payload 返回当前投影，不同 payload 返回稳定 409。
- Reversal 引用原 Payment，要求正数 amount、currency、reason 和 actor；允许多次部分冲正，
  累计金额不能超过原付款。Reversal 同样不可编辑或删除。
- `OPEN/PARTIALLY_PAID/PAID/OVERDUE` 由 original amount、effective payment、due date 与 UTC Clock
  统一推导。全额付款发布 `ReceivablePaidV1`；冲正后重新推导，不保留虚假的 PAID 状态。
- 逾期扫描只处理 due date 早于当天且仍有余额的应收，使用有界 `FOR UPDATE SKIP LOCKED` 批次，
  重复扫描不重复发布 `ReceivableOverdueV1`。

## 4. API 与页面

- `GET /api/v1/receivables`：状态过滤与不透明游标分页。
- `GET /api/v1/receivables/{receivableId}`：余额、付款/冲正事实、时间线、版本与允许动作。
- `POST /api/v1/receivables/{receivableId}/payments`：要求 `If-Match` 与 `Idempotency-Key`。
- `POST /api/v1/receivables/{receivableId}/payments/{paymentId}/reversal`：独立冲正权限、原因与幂等键。
- `/app/receivables` 提供应收队列；详情页提供付款 dialog 和带二次确认的高风险冲正 dialog。
- 409/412 返回稳定 code；前端刷新最新余额与版本后再允许用户重试。

## 5. 权限与字段

- Finance Specialist：读取商业金额、登记付款、执行冲正。
- Tenant Administrator：拥有相同结算权限，但仍不能绕过金额、状态、版本或幂等规则。
- Buyer：只读取身份绑定组织的应收金额、余额、到期日与状态，不返回付款/冲正明细和内部 actor。
- Auditor：默认可读状态与时间证据，金额、参考号、actor 和原因掩码。
- System Operator：无 `settlement:read`，技术运维权限不推导商业金额访问。
- 所有查找先限定 tenant；同租户无归属或跨租户资源按不存在返回。

## 6. 数据与契约兼容性

- V20 只新增 `settlement` schema，不重写 V2～V19，也不建立跨模块 FK。
- OpenAPI 1.11 对既有契约做增量扩展；冲正请求明确包含部分冲正 amount。
- AsyncAPI 1.4 新增付款、冲正、付清和逾期事件，`ReceivableCreatedV1` 只做兼容性字段扩展。
- 事件 envelope 保留 tenant、subject、correlation 与 causation；财务事实和历史在业务事务内可靠发布。

## 7. 验证入口

```bash
./mvnw -pl backend -am verify
pnpm --dir frontend test:coverage
pnpm --dir frontend build
make settlement-e2e
python3 scripts/validate_repository.py --scope public
```

核心证据包括 `ReceivableTest`、`SettlementIntegrationTest`、`settlement.test.ts`、
`SettlementWorkspace.test.tsx` 和真实 OIDC/PostgreSQL/React Playwright 全链路。
