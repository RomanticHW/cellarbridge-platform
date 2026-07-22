# 履约编排运行手册

## 1. 当前边界

Task 09 将已确认的 Inventory Reservation 转换为路线专属 Fulfillment Plan，并把履约进度可靠地同步到 Trade Order。当前实现属于可重复验证的合成业务演示，不调用真实仓储、报关、承运商或签收系统，也不对法规、时效或承运价格作生产承诺。

Task 09 处于 **Available**。步骤失败和超时会发布不可变事实，并由 Exception Center 按稳定源键去重建单；源履约状态仍由 Fulfillment 独占。

## 2. 可观察行为

- 当前生产者的 `InventoryReservationConfirmedV1` 必须携带受支持的 `routeCode`；未知路线会 fail closed，不创建通用兜底计划。升级前已发布且缺少路线的合法 V1 历史事件会被确认，但不会凭不完整事实追溯创建计划。
- V18 为 `SH_GENERAL_TRADE`、`NB_BONDED_B2B` 和 `HK_FREE_TRADE` 保存独立的版本化模板。计划创建时冻结模板版本、步骤与依赖快照，后续模板变更不会改写在途计划。
- 步骤只有在依赖满足后才进入 `READY`；`START / COMPLETE / FAIL / RETRY / SKIP` 由责任角色、计划版本和 `Idempotency-Key` 共同约束。
- `COMPLETE` 通过确定性模拟适配器执行 `SUCCESS / FAILURE / DELAY` 场景，并保存可查询的尝试证据。重复命令重放既不重复适配器调用，也不重复事件。
- SLA 扫描将到期的活动步骤一次性标记为 `OVERDUE` 并发布事件；重复扫描不产生第二次状态变化。
- Exception Center 的受控恢复可将 `FAILED` 步骤重置为依赖校验后的 `READY/BLOCKED`，或为 `OVERDUE` 步骤重建同长度 SLA 窗口；恢复命令按幂等键执行，异常只在回读到活动源状态后解决。
- 仅 `customerVisible` 步骤生成客户里程碑。内部失败代码、责任角色、依赖和适配器证据不会进入 Buyer 时间线。
- Trade Order 通过可靠事件依次从 `RESERVED` 进入 `READY_FOR_FULFILLMENT`、`IN_FULFILLMENT` 和 `FULFILLED`。

## 3. 权限和租户

- `fulfillment:read`：读取租户内 Fulfillment board/detail；
- `fulfillment:operate`：执行步骤动作；
- 责任角色必须与当前身份的稳定 role code 匹配；显示名称不参与授权；
- 所有读取、命令、幂等记录、适配器尝试和事件都带 `tenant_id`；跨租户资源按不存在处理；
- Customer Buyer 不拥有内部 Fulfillment 读取权限，只能从自身 Order 时间线读取公开里程碑。

## 4. API 与页面

- `GET /api/v1/fulfillment/plans`：状态、责任角色、逾期和订单过滤，使用不透明游标分页；
- `GET /api/v1/fulfillment/plans/{planId}`：计划快照、依赖、步骤、里程碑和最新模拟适配器证据；
- `POST /api/v1/fulfillment/plans/{planId}/steps/{stepId}/actions`：要求 `If-Match` 和 `Idempotency-Key`；版本冲突返回稳定 Problem Details；
- `/app/fulfillment`：Fulfillment board；
- `/app/fulfillment/{planId}`：依赖图、步骤动作、SLA、失败与模拟证据；
- `/app/orders/{orderId}`：履约开始后提供计划深链，Buyer 只显示公开时间线。

## 5. 本地验证

```bash
./mvnw -pl backend -am verify
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend lint
pnpm --dir frontend build
make fulfillment-e2e
python3 scripts/validate_repository.py --scope public
```

关键自动证据包括 `FulfillmentTemplateTest`、`FulfillmentOrchestrationIntegrationTest`、`TradeOrderFulfillmentEventServiceTest`、Fulfillment API/React 组件测试和真实 OIDC/PostgreSQL Playwright 流程。

## 6. 兼容性

- V18 只新增 `fulfillment` schema，不改写 V2–V17；迁移归属和 SHA-256 记录在 `migration-ownership.csv`。
- `InventoryReservationConfirmedV1.routeCode` 是 additive optional schema 变化，以保持旧载荷可反序列化；消费端对缺少路线的载荷只做兼容确认、不创建计划，未知路线则 fail closed。
- 新增 Fulfillment V1 事件和 OpenAPI 1.9 端点，不改变既有 Buyer DTO。事件使用至少一次投递，消费端按 event id 去重。
- 三条种子路线和模拟场景均为合成演示数据，不代表生产承运或海关集成。
