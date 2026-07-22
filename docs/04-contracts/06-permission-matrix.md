# 权限矩阵

## 1. 角色

- Sales Representative（销售代表）
- Sales Manager（销售经理）
- Trade Operator（贸易运营）
- Warehouse Operator（仓库操作员）
- Finance Specialist（财务）
- Tenant Administrator（租户管理员）
- Auditor（审计只读）
- Customer Buyer（客户采购）
- System Operator（系统操作员）

角色是默认模板；最终后端按权限码 + 属性判断。

## 2. 权限矩阵

Legend：`R` 读，`C` 创建，`U` 执行动作/更新，`A` 审批/高风险，`—` 无。

| 能力              |        Sales | Sales Mgr | Trade Ops |        Warehouse |     Finance | Tenant Admin | Auditor |        Buyer | System Ops |
| ----------------- | -----------: | --------: | --------: | ---------------: | ----------: | -----------: | ------: | -----------: | ---------: |
| 客户列表/详情     |            R |         R |         R |                — | R(必要字段) |            R |       R | 自己组织有限 |          — |
| 创建/编辑客户草稿 |    C/U(本人) |       C/U |         — |                — |           — |          C/U |       R |            — |          — |
| 客户审核/激活     |            — |         A |         — |                — |           — |            A |       R |            — |          — |
| 商品/SKU 查询     |            R |         R |         R |                R |           R |            R |       R |         有限 |          — |
| 商品维护          |            — |         — |         — |                — |           — |    U（演示） |       R |            — |          — |
| 库存查询          |         摘要 |      摘要 |         R |      R(分配仓库) |           — |            R |       R |            — |   技术摘要 |
| 库存调整          |            — |         — |         — | U(分配仓库,专权) |           — |            A |       R |            — |          — |
| 创建/编辑报价     |    C/U(本人) |       C/U |         R |                — |     R(有限) |            R |       R |            — |          — |
| 查看成本/毛利     |   按授权可选 |         R |  按需有限 |                — |           R |            R |  按授权 |            — |          — |
| 提交/发送报价     |      U(本人) |         U |         — |                — |           — |            U |       R |            — |          — |
| 审批报价          |            — |         A |         — |                — |           — |      A(豁免) |       R |            — |          — |
| 接受/拒绝报价     |            — |         — |         — |                — |           — |            — |       R |  U(自己组织) |          — |
| 订单查看          |      R(负责) |         R |         R |      R(任务关联) |           R |            R |       R | 自己组织有限 |   技术摘要 |
| 订单取消          |         发起 |         A |      建议 |                — |    财务确认 |            A |       R |     请求(P2) |          — |
| 履约步骤          |            R |         R |         U |      U(仓库步骤) |       R有限 |          R/A |       R |   公开里程碑 |          — |
| 异常查看/处理     |        R关联 |         R |       U/A |            U关联 |       U关联 |            A |       R |     公开摘要 |   技术异常 |
| 应收查看          |         摘要 |     R摘要 |         — |                — |           R |            R | R按授权 | 自己组织余额 |          — |
| 付款登记/冲正     |            — |         — |         — |                — |         U/A |            A |       R |            — |          — |
| 驾驶舱            |         个人 |      团队 |      运营 |             仓库 |        财务 |       全租户 |    只读 |            — |       技术 |
| 审计              | 自己对象摘要 |      团队 |      关联 |             关联 |        关联 |            R |       R |   公开时间线 |   技术审计 |
| 事件重放          |            — |         — |         — |                — |           — |            — |       R |            — |          A |
| 用户/权限配置     |            — |         — |         — |                — |           — |            A |       R |            — |          — |

## 3. 属性规则

- Sales 默认只能写本人 owner 的草稿；经理可团队范围；
- Buyer 的 partnerId 由身份映射，不接受请求指定其他 partner；
- Warehouse 受 warehouse assignment；
- 审批人不能是提交人；
- Trade Operator 不能查看成本，除非单独权限；
- System Operator 有技术 publication/replay，但不自动有商业字段；
- Auditor 只读且不自动包含个人/商业敏感原值；
- Tenant Admin 不能绕过所有业务状态机。

Task 04 的稳定权限码与字段规则：

- `catalog:read` 与 `inventory:read` 必须同时具备，才可访问内部 Catalog + 供给摘要页；
- `inventory:read-exact` 只提升到精确数量/批次，并继续受 tenant 与 warehouse assignment 限制；
- `inventory:reserve` 独立授权幂等 release/consume；当前演示模板仅 Warehouse Operator 与 Tenant Administrator 获得，且不隐含跨 tenant 或未分配仓库的精确字段读取；
- `inventory:adjust` 不由查询权限隐式授予；Task 04 没有库存写入口；
- Buyer 即使拥有有限 `catalog:read`，也不因此获得内部 `inventory:read` 或供给页访问；
- Sales/Manager 只看数量带，Trade/Warehouse/Tenant Admin 仅在显式授予 exact 权限后看授权范围精确值。

Task 11 的稳定结算权限码与字段规则：

- `settlement:read` 只建立租户内读边界；Buyer 额外固定为身份映射的 `partnerId`，请求不能指定其他组织；
- `settlement:read-commercial-sensitive` 才允许内部角色读取金额、外部付款参考号、actor 和冲正原因；
- `settlement:record-payment` 与 `settlement:reverse-payment` 独立授权，Buyer 即使可读也不能写；
- Finance Specialist 和 Tenant Administrator 拥有结算写权限；Auditor 默认只读且金额掩码；
- System Operator 不拥有 `settlement:read`，技术事件权限不推导商业应收访问。

Task 12 的稳定审计报表权限码与字段规则：

- `reporting:read` 是 dashboard 与 work queue 的基础门禁；个人队列仍按候选权限、assignee/owner 过滤；
- 团队队列只允许 Sales Manager 与 Tenant Administrator，不能通过 `scope=team` 提升普通角色；
- `audit:read` 独立保护全局审计搜索；Sales Representative 继续固定为本人 actor 范围；
- timeline 复用源对象权限：Partner/Quotation/Order 分别要求对应 read 权限，Buyer 还受身份绑定 partner 限制；
- Finance dashboard 只投影应收状态类指标；System Operator 只看技术敏感审计分类；所有查询先限定 tenant。

## 4. 字段矩阵

| 字段类别                     |          内部普通 | 商业敏感权限 |                        Buyer |   System Ops |           Audit |
| ---------------------------- | ----------------: | -----------: | ---------------------------: | -----------: | --------------: |
| 客户名称/地址                |            按业务 |           是 |                         自己 |      否/掩码 |     掩码/按授权 |
| 单价/总额                    |            按业务 |           是 |                自己报价/订单 |           否 |          按授权 |
| 成本/毛利                    |                否 |           是 |                           否 |           否 |       特批/摘要 |
| 库存精确批次                 |     Ops/Warehouse |           是 |                           否 | 技术 ID 可选 |          按授权 |
| 内部路径评分                 | Sales/Manager/Ops |           是 |                           否 |           否 |            摘要 |
| 公开里程碑                   |                是 |           是 |                         自己 |         技术 |              是 |
| 内部异常评论                 |          关联角色 |           是 |                           否 | 技术错误摘要 |          按授权 |
| 应收金额/付款参考号/冲正原因 |           否/摘要 |           是 | 自己组织金额；不返回付款明细 |           否 | 默认掩码/按授权 |
| token/secret                 |                否 |           否 |                           否 |           否 |              否 |

## 5. 验收

权限测试采用角色 × 资源归属 × 状态 × tenant × 字段矩阵，不能只测试“管理员能访问”。
