# 合成演示数据设计

## 1. 原则

- 不复制真实酒款、客户、价格、库存或员工；
- 名称明确 synthetic/fictional；
- 数据能触发主要规则和失败分支；
- 固定 seed 可重现；
- 不使用目标公司 logo/商标资产；
- 价格只为演示计算。

## 2. 租户

- `CellarBridge Demo Trading`（主演示）；
- `Northstar Isolation Test`（跨租户安全验证）。

## 3. 用户

| 用户 | 角色 | 说明 |
|---|---|---|
| sales.demo | Sales Representative | 主报价 owner |
| manager.demo | Sales Manager | 审批 |
| trade.demo | Trade Operator | 履约 |
| warehouse.demo | Warehouse Operator | 仓库步骤 |
| finance.demo | Finance Specialist | 付款 |
| admin.demo | Tenant Administrator | 配置/访问 |
| auditor.demo | Auditor | 只读证据 |
| buyer.demo | Customer Buyer | 绑定合成客户 |
| ops.demo | System Operator | publication/replay |

密码只在本地 demo profile 文档，非生产。

## 4. 客户

- 锦城餐饮集团（合成）：ACTIVE、CNY、30 天、上海/宁波资格；
- 青岚精品零售（合成）：ACTIVE、预付、香港资格；
- 星河分销（合成）：PENDING_REVIEW，用于审核；
- 停用客户，用于拒绝场景；
- isolation tenant 客户同名，用于证明 tenant scope。

## 5. 商品

合成 producer/region/category。独立 benchmark 使用 4,000 Product / 12,000 SKU / 36,000 供给投影 / 36,000 lot；主演示包含：

- CB-MTV-2019-750X6 与 2021 年 1.5L 变体；
- CB-ETB-2022-750X6；
- CB-YL-2020-750X6（中文检索）；
- CB-NFS-NV-750X6；
- 包含 NV、不同容量/箱规、停用 SKU、模糊检索词。

## 6. 供给

- Eastbank 国内仓 DOMESTIC_ON_HAND；
- Harborlight 保税池 BONDED_ON_HAND；
- Harbor Quay 香港仓 HONG_KONG_ON_HAND；
- 在途预售/海外代采 reference pools；
- 热点批次总量不足以满足两个并发订单；
- 多批次相同 SKU 用于稳定分配；
- 冻结/不可用批次。

## 7. 策略

- `TRP-2026-01` 权重 40/30/20/10；
- `QAP-2026-01` 折扣 >8%、毛利 <15%、非推荐路径、账期超默认；
- `PRICE-2026-01` 金额舍入；
- 三个 fulfillment template 各 5~8 步；
- SLA 时间压缩用于演示 profile，业务文案明确。

## 8. 场景数据

- 自动批准报价；
- 需审批报价；
- 过期报价；
- 接受但订单处理中；
- 预占成功；
- 预占失败缺口；
- 履约适配器失败/恢复；
- 部分付款/逾期/冲正；
- event duplicate/backlog（只 test/demo 控制）。

## 9. 生成与重置

- deterministic Faker/Java generator seed `20260713`；
- base fixtures 与 benchmark data 分开；
- `make demo-reset` 只在 profile=demo、本地 host、明确确认；
- reset 重建 demo tenant，不触及其他 DB；
- CI fixture 由测试独立创建。
