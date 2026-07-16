# 报价与贸易路径运行手册

Status: **Available in Task 05**

Requirements: **UC-QUO-001–003, UC-TRD-001, FR-QUO-001–009, FR-TRD-001–006**

## 1. 可观察行为

- Sales 从活动客户与活动 SKU 创建报价草稿；金额使用 `BigDecimal`、四位小数与 `HALF_UP`，支持箱/瓶换算、折扣、人工单价和路线费用分摊。
- 每次报价保存捕获客户、SKU、价格来源版本；已提交修订不可修改，变更请求后的再次保存创建新修订。
- Planning 将当前评估升级为 `ROUTE-2026-03`：MOQ 只使用 case-equivalent；精确覆盖、自动 Supply Type 与 confidence 统一来自 `SUPPLY-DECISION-2026-01`，不拆/合箱、不跨单位或 Supply Type 求和，Fixed Pool 不 fallback，Auto 不冻结 Pool。
- 一次评估使用一个微秒对齐时间；canonical input schema 3 的确切序列化字符串产生 input hash，selected route 的 Supply Decision 与候选使用同一次 Policy 结果并由 V12 同事务持久化。
- 非推荐路线只能由经理填写理由后覆盖；评估输入摘要、策略版本、原推荐、操作者和发生时间持久化。
- 折扣、毛利、账期、人工/异常价格、非推荐路线和有效期规则产生审批要求。提交者不能审批自己的修订；并发重复审批由版本条件与唯一约束收敛为一条决策。
- 路线评估在同一事务复制 Planning selected-route Decision 到当前 Revision；按 `quotationLineId` 严格匹配 AUTO/FIXED 行。签发复核原 Evaluation，不重新推荐路线或重查库存。
- 只有仍在有效期且拥有 `FROZEN` Decision 的已批准报价可以签发。签发生成高熵令牌，只保存 SHA-256 摘要；公开 API 使用明确 allow-list 和 `Cache-Control: no-store`。
- Task 05 的交付边界停在只读预览；当前仓库已由 Task 06 增加客户接受/拒绝与终态回执，详见 `17-customer-quotation-acceptance-runbook.md`。转订单和库存预占仍属于后续任务。

## 2. 模块与数据归属

`quotation` 和 `tradeplanning` 均为 `com.rom.cellarbridge` 的直接子模块。Quotation 只调用 Partner、Catalog 与 Trade Planning 的公开接口；不会直接依赖 Inventory。Trade Planning 通过 Partner、Catalog 与 Inventory 的 tenant-explicit 查询接口组合评估输入。

`V6` 保存评估与候选；Planning-only V12 保存 selected-route Decision；quotation-only V13 保存独立 Revision 副本与行模式。FROZEN 是分配约束，不是 Reservation，AUTO 不冻结 Pool/Lot。

Propagation review 以 Current V1 将同一 Hash 传播到 trade_order-only V14；Legacy 输入保持不可验证且阻止 Reservation。

策略版本固定为：

| Policy | Version |
|---|---|
| Route evaluation | `ROUTE-2026-03`（历史 `ROUTE-2026-01/02` 仍可读取） |
| Supply decision | `SUPPLY-DECISION-2026-01`, schema 1 |
| Pricing | `PRICE-2026-01` |
| Approval | `APPROVAL-2026-01` |

## 3. 权限与字段安全

| 行为 | 权限与约束 |
|---|---|
| 查看报价 | `quotation:read`；Sales 仅本人，经理可管理当前 tenant |
| 创建/修改/评估 | `quotation:create`；仅草稿/变更请求状态，所有写入使用 `If-Match` |
| 提交 | `quotation:submit`；需已选择合格路线、`FROZEN` Decision 且未过期 |
| 审批 | `quotation:approve`；不能是本修订提交者 |
| 签发 | `quotation:issue`；需已批准、未过期且原 Planning Evaluation 与冻结副本一致 |
| 毛利字段 | `quotation:read-commercial-sensitive` |
| 公开 Portal | 随机令牌；Task 06 支持 SENT 与客户终态安全投影；不返回成本、毛利、路线评分、输入摘要、内部 actor 或策略证据 |

控制器不接受 tenant/user 参数。内部查询和更新显式重复 tenant predicate；令牌摘要查询是受注解约束的全局注册表入口，返回后仍以报价记录自身 tenant 读取完整数据。

## 4. 本地演示

`demo` profile 提供活动客户 `Aurora Market Services`、六个合成 SKU 的 CNY 价格引用和角色权限。业务可见字段未写入工具或生成来源标记。

```bash
make dev-core
# open http://localhost:5173/app/quotations

make quotation-e2e
```

`make quotation-e2e` 使用隔离 Compose project 与新 volume，真实登录 `north.sales` 从 Catalog 选择 SKU，创建 9% 折扣报价、评估路线并提交；再真实登录 `north.manager` 独立审批、签发并打开只读客户安全预览。脚本验证公开页不显示毛利、路线评分或策略版本，结束后自动清理。

## 5. 验证证据

- `QuotationPricingPolicyTest`：1,000 组生成输入、箱/瓶换算、精度与舍入、费用分摊、重复 SKU 和折扣边界。
- `RouteEvaluationPolicyTest`：跨单位拒绝、MOQ/精确数量分离、pool/confidence 边界、稳定推荐和策略版本。
- `QuotationAggregateTest`：修订冻结、新修订、状态迁移和自审拒绝。
- `QuotationApiIntegrationTest`：真实 PostgreSQL 18.4 空库迁移、权限、经理覆盖、快照不可变、并发重复审批、签发、401/404、跨租户和公开字段 allow-list。
- `QuotationWorkspace.test.tsx`、`quotationForm.test.ts`、`quotations.test.ts`：生成契约客户端、表单精度、恢复/并发提示、解释视图与无认证公开路由。
- `quotation-trade-planning.live.spec.ts`：真实 OIDC 主链路和客户安全预览。
- `ModularityTest` / `ArchitectureRulesTest`：根模块发现、层边界、tenant-explicit repository API 与禁止 `Quotation → Inventory`。

## 6. 已知限制

- Task 05 自身不实现客户决定；当前仓库的接受/拒绝见 Task 06 运行手册，幂等报价转订单见 Task 07 运行手册。库存预占与履约仍未实现。
- 报价列表当前面向演示规模；接口返回稳定页结构，但深分页优化可在真实数据规模需要时引入。
- 签发后的通知投递和外部事件 broker 发布属于后续可靠事件/通知任务；Task 05 只在 Quotation 自有表内记录发布证据与待办。
- 路线与价格是确定性合成策略，不代表实际海关、税务、承运商报价或生产承诺。Planning 已有内部 route-bound evidence，但 Quotation freeze、事件/订单传播和库存预占均未实现。
