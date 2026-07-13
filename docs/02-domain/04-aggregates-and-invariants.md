# 聚合与业务不变量

## 1. Partner

### 状态

`DRAFT → PENDING_REVIEW → ACTIVE | CHANGES_REQUESTED | REJECTED`；ACTIVE 可停用为 `SUSPENDED`，经重新审核恢复。

### 不变量

1. 法定名称、客户类型、默认币种、至少一个联系人和账单地址在提交时必填；
2. 租户内统一识别号（存在时）唯一；
3. 路径资格包含生效区间，不能有相互矛盾的重叠配置；
4. 信用额度和账期不能为负；
5. 提交人不能批准自己的申请，除非启用有审计原因的演示豁免；
6. 审核决定不可修改或删除；
7. SUSPENDED 客户不能新建/发送报价，但历史交易保持可读。

### 典型方法

`submitForReview(actor, clock)`、`approve(decision, reviewer)`、`requestChanges(reason)`、`suspend(reason)`、`snapshotForQuotation()`。

## 2. WineProduct / SKU

### 不变量

- Product 必须有稳定名称、生产者和分类；
- SKU 唯一键为 `(tenant, product, vintage/NV, volume, unitsPerCase, packageType)`；
- volume > 0，unitsPerCase > 0；
- 已激活 SKU 的关键识别维度不能原地改变，应停用并创建新 SKU；
- 被交易引用的 SKU 不物理删除；
- 商品快照必须包含 SKU 编码、显示名、年份、容量和包装。

## 3. InventoryLot

### 数量方程

```text
on_hand >= 0
reserved >= 0
reserved <= on_hand
available = on_hand - reserved
```

### 原子操作

- 预占：仅当 `on_hand - reserved >= requested` 时 `reserved += requested`；
- 释放：仅当对应 allocation 尚未释放/消费，`reserved -= quantity`；
- 消费：`reserved -= quantity` 且 `on_hand -= quantity`；
- 调整：由专用库存调整命令记录 movement，不直接覆盖数值。

所有动作使用受影响行数判断成功，不能先读后写作为唯一保护。

## 4. InventoryReservation

### 状态

`PENDING → CONFIRMED | FAILED`；CONFIRMED 可逐步 `RELEASED` 或 `CONSUMED`，P1 要求所有 allocation 最终一致。

### 不变量

1. `(tenant_id, order_id)` 唯一；
2. 每个订单行请求数量必须正且只出现一次；
3. P1 确认时所有订单行分配总和等于请求数量；
4. 任一行不足则事务回滚，不保留新 allocation；
5. allocation 只能释放或消费一次；
6. 释放/消费重复命令返回既有结果；
7. 失败结果保存请求、观察可用和缺口，但不声称为强一致库存快照。

## 5. Quotation

### 不变量

1. 报价属于一个 ACTIVE 客户；
2. 当前修订至少一行、最多 50 行；
3. 每行 SKU 唯一（P1 不允许同 SKU 多行）；
4. 数量正数，金额币种一致；
5. 所有金额按币种精度和统一舍入计算；
6. 提交时存在至少一条有效路径，选择路径必须有效；
7. 选择非推荐路径需要权限和理由；
8. 提交后修订冻结；修改需从 `CHANGES_REQUESTED` 创建新修订；
9. 只有 `APPROVED` 修订能发送；
10. 只有 `SENT` 且未过期/撤销的修订能被接受；
11. 接受、拒绝、撤销和过期为互斥终态；
12. 接受命令按报价修订和客户操作幂等；
13. 发出/接受后商品、客户、价格、路径和条款快照不可修改。

### 金额公式

```text
lineGross = unitListPrice × quantity
lineDiscount = round(lineGross × discountRate)
lineNet = lineGross - lineDiscount + allocatedCharges
subtotal = Σ lineNet
quoteTotal = subtotal + quoteLevelCharges + taxPlaceholder - quoteLevelDiscount
```

P1 税费为演示配置项，不计算真实税法。所有中间舍入规则必须写入 `PricePolicyVersion`。

## 6. RouteEvaluation

### 不变量

- 每条候选路径恰有一个 `ELIGIBLE` 或 `REJECTED` 结果；
- REJECTED 至少一个拒绝原因，无评分/不可选择；
- ELIGIBLE 无硬约束拒绝，并具有四个子分和总分；
- 分数 0~100，权重非负且和为 1；
- 同分排序使用 route priority 和 route code 稳定打破；
- 推荐路径必须是第一条有效路径；
- 评估保存输入 hash、策略版本、时钟时间；
- 历史评估不因策略更新重算。

## 7. TradeOrder

### 不变量

1. `(tenant_id, source_quotation_id)` 唯一；
2. 订单只能由 ACCEPTED 报价快照创建；
3. 订单行和总额必须与接受快照一致；
4. 商业快照不可修改；
5. 状态只能按状态机变化；
6. 库存成功前订单不能进入 READY_FOR_FULFILLMENT；
7. 已出库或已完成订单不能普通取消；
8. 取消必须有原因和 actor；
9. 相同命令/事件重复不产生第二个订单或状态事件。

## 8. FulfillmentPlan

### 不变量

- 一个订单最多一个当前 P1 计划；
- 模板版本、步骤和依赖在创建时冻结；
- 依赖形成有向无环图；
- 未满足依赖的步骤不能开始；
- 终态步骤不能再次产生完成副作用；
- 失败和逾期必须产生检测事实；
- 公开里程碑不含内部评论、成本和适配器错误；
- 计划完成要求所有必需步骤 COMPLETED，取消步骤必须由模板允许；
- 计划取消需与订单取消状态协调，但不跨库直接修改。

## 9. ExceptionCase

### 不变量

1. `(tenant, source_type, source_id, exception_type, open_scope)` 防止重复开放异常；
2. 新异常保存不可变检测事实；
3. 分派和转派保存历史；
4. 恢复动作必须来自允许目录；
5. 每次恢复尝试有唯一 ID 和幂等键；
6. 只有源对象达到预期恢复状态，异常才可解决/关闭；
7. 调查记录不可修改，纠错使用追加说明；
8. 关闭后新问题创建新异常，不重新打开历史对象（P1）。

## 10. Receivable

### 数量方程

```text
originalAmount > 0
paidNet = Σ(payment.amount) + Σ(reversal.amount)
0 <= paidNet <= originalAmount
outstanding = originalAmount - paidNet
```

### 不变量

- 一个触发业务键只创建一个应收；
- 所有记录币种一致；
- 付款正数，冲正负数且引用原付款；
- 外部参考号在租户+来源范围唯一；
- 不允许超额付款；
- 原付款不删除/改金额；
- 到期且余额 > 0 才是 OVERDUE；
- PAID 后冲正可回到 PARTIALLY_PAID/OPEN。

## 11. 业务不变量的实现层级

| 规则 | 首选实现 | 最终防线 |
|---|---|---|
| 状态转换 | 聚合方法 | 状态条件 UPDATE/版本冲突 |
| 报价对应唯一订单 | 应用幂等检查 | 数据库唯一约束 |
| 库存不超卖 | 应用稳定分配 | 原子条件 SQL + 事务 |
| 重复事件 | Inbox 检查 | event_id 唯一约束 |
| 租户隔离 | 安全上下文/Repository 规范 | 查询谓词、复合唯一键、测试 |
| 金额正确 | Money 值对象 | DB 精度/约束、属性测试 |
| 已发送快照不可变 | 聚合无修改方法 | 表权限/状态条件和测试 |

单纯 UI 禁用、先查询再判断或内存锁都不是最终防线。
