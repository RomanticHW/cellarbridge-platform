# 酒款与供给检索运行手册

Status: **Available in Tasks 04 and 07B**

Requirements: **UC-CAT-001, UC-INV-001, FR-CAT-001–004, FR-INV-001–003**

## 1. 可观察行为

- `/api/v1/catalog/skus` 只返回当前租户的 `ACTIVE` SKU，支持关键词、生产者、产区、国家、分类、年份、容量、供给类型、CASE/BOTTLE 数量单位、可用性、预计日期、是否可自动预占和白名单排序组合过滤。
- 英文、中文、重音规范化和常见拼写偏差由 PostgreSQL FTS 与 `pg_trgm` 共同处理；任意 wildcard 和非白名单排序会返回稳定 400。
- 列表采用 HMAC 游标，游标绑定 tenant、全部规范化过滤条件和排序，不能跨租户或跨查询复用。
- 停用 SKU 不进入新增选择列表，但授权用户仍可通过 `/api/v1/catalog/skus/{skuId}` 历史读取。
- 每条供给显示 CASE/BOTTLE 单位、分类、数量带、预计日期、`dataAsOf` 和非承诺免责声明。同一 pool/SKU 的不同单位是独立摘要，永不换算或相加。Sales 不获得精确数量、批次、仓库优先级或仓库版本；具有 `inventory:read-exact` 的 Warehouse/Ops 只看到其 warehouse assignment 覆盖的批次，并获得单位、priority/version 证据。未分配库存池仍保留普通汇总但隐藏精确字段。
- React 工作台将组合过滤写入 URL、对关键词请求执行 300ms debounce、支持游标前后翻页、loading/empty/error/403，并把 SKU 加入仅存在于当前页面内存的“待报价选择区”。该操作不创建报价、订单或库存预占。

## 2. 模型与数据归属

`V4__catalog_products_and_search_projection.sql` 创建 Catalog 自有的 Producer、Region、WineProduct、SKU 和 `sku_supply_projection`。`V11__catalog_supply_projection_quantity_unit.sql` 将投影主键扩展为 `(tenant_id, sku_id, supply_pool_id, quantity_unit)`，历史行解释为 CASE，并增加单位约束和过滤索引。SKU 编码及“product + vintage/NV + volume + units per case + package type”组合在 tenant 内唯一，容量和箱规必须为正数。

`V5__inventory_supply_model.sql` 创建 Inventory 自有的 Warehouse、SupplyPool、Lot 和 warehouse assignment。`V10__inventory_quantity_unit_and_warehouse_priority.sql` 将历史 Lot 解释为 CASE，要求所有 Lot 显式保存 CASE/BOTTLE，并为 Warehouse 增加非负 `allocation_priority`（历史默认 100）。较小 priority 只是 Task 08 设计分配策略的输入；当前查询不会执行分配。供给类型固定为：

- `DOMESTIC_ON_HAND`
- `BONDED_ON_HAND`
- `HONG_KONG_ON_HAND`
- `IN_TRANSIT_PRESALE`
- `OVERSEAS_SOURCING`

只有前三类的 generated `automatically_reservable` 为 true。Inventory 的 `sku_id` 是逻辑 Catalog 标识，不建立跨模块外键，也不 join Catalog 表。Catalog 通过公开、tenant-explicit 的 `CatalogSearchQuery` 提供 SKU 与单位化搜索投影；Inventory 应用服务按已批准的 `Inventory → Catalog` 依赖方向编排相同 pool/SKU/unit 的精确批次和字段权限。搜索投影归 Catalog 所有并显式保存 `data_as_of`，Inventory 仍是精确批次事实来源。

## 3. 搜索与契约

OpenAPI 1.5.0 实现 `/catalog/skus` 和 `/catalog/skus/{skuId}`，并显式声明 CASE/BOTTLE 查询过滤、每条供给的 `quantityUnit`、400/401/403/404、nullable 精确数量、授权批次的 priority/version、页面 `dataAsOf` 和免责声明。所有 Lot Quantity 的单位必须与所属供给摘要一致；TypeScript 边界由同一契约生成。

搜索索引包括：

- `search_document` 的 GIN FTS 索引；
- `search_text gist_trgm_ops(siglen=64)` 的 GiST trigram 索引；
- tenant/status/dimension B-tree 索引；
- 供给过滤、`ix_catalog_supply_projection_unit_filter` 与 Inventory 的 tenant/SKU/unit 可用批次索引。

关键词候选由 FTS、整串 trigram 和 word-similarity 三个索引友好分支合并，再应用 tenant、供给条件、稳定排序和页大小。代表性计划实际使用 `ix_catalog_sku_search_trigram` 的普通与 bitmap index scan；不通过 `enable_seqscan=off` 等 planner 开关制造证据。

## 4. 权限与隔离

| 视图 | 必需权限 | 范围与字段 |
|---|---|---|
| Catalog + 供给摘要 | `catalog:read` + `inventory:read` | 当前 tenant；数量带，不含精确数量/批次 |
| 精确库存 | 再加 `inventory:read-exact` | 当前 tenant 且仅 warehouse assignment 覆盖的 supply pool |
| Buyer | 无内部供给访问 | 导航隐藏；直接访问由后端返回 403 |

控制器从不接受 tenant、user 或 warehouse scope 参数。租户和用户来自已验证 token 建立的 `TenantContext`；所有 Catalog、projection、Inventory lot 和 assignment 查询均重复包含 tenant predicate。精确批次按 `(supplyPoolId, skuId, quantityUnit)` 聚合，防止同一供给池内其他 SKU 或不同单位的数量和批次混入。

## 5. 合成演示与本地运行

`demo` profile 提供虚构生产者、产区、NV/多年份、多容量/箱规、停用 SKU、五种供给、两个租户和仓库授权：

| Username | Search visibility |
|---|---|
| `north.sales` / `north.manager` | North tenant 摘要 |
| `north.admin` / `north.trade` | North tenant 全部已授权精确批次 |
| `north.warehouse` | North tenant，仅分配仓库精确批次 |
| `north.buyer` | 内部供给页拒绝 |
| `harbor.manager` | Harbor tenant 摘要，验证同名 SKU 隔离 |

密码只用于本地 demo profile：`CellarBridge-Demo-2026!`。

```bash
make dev-core
# open http://localhost:5173/app/catalog

make catalog-e2e
make catalog-benchmark
```

`make catalog-e2e` 使用隔离 Compose project 和新 volume：真实登录 `north.sales` 验证同一 pool/SKU 的 CASE/BOTTLE 摘要且无精确字段；登录 `north.warehouse` 验证 assignment 范围内的 Lot 单位、priority/version 和未分配 pool 隐藏；登录 `harbor.manager` 验证第二租户隔离；登录 `north.buyer` 验证导航隐藏和直接访问 403。全程断言没有业务写请求，脚本结束后自动清理。

## 6. 性能证据

本地执行日期：**2026-07-15**。脚本使用独立 PostgreSQL 18.4 容器，从空库应用 V4/V5/V10/V11，确定性写入 **12,000 SKU、36,000 单位化供给投影、36,000 Inventory lot**；SQL 门禁确认 projection 与 lot 各有 **4,000** 个同 tenant/SKU/pool 的 CASE+BOTTLE 组。执行 `ANALYZE`，热身 5 次后测量 30 次；ARM64 macOS 的 Docker Desktop 分配 10 CPU、8,321,515,520 bytes memory。

代表查询为 tenant 内关键词 `starling` + `DOMESTIC_ON_HAND` + `BOTTLE` + `AVAILABLE`，限制 26 行，并执行 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`：

| Metric | Result |
|---|---:|
| Execution p50 | 7.971 ms |
| Execution p95 | 11.478 ms |
| Sample min / max | 7.262 / 16.838 ms |
| Representative execution | 7.567 ms |
| Representative planning | 2.915 ms |
| Top-level shared hit / read blocks | 2,911 / 0 |

主搜索计划包含 trigram GiST `Index Scan` / `Bitmap Index Scan`、供给投影和产品主键 `Index Scan`。planner 对相关供给子查询选择旧组合索引并把 `quantity_unit` 保留为 Filter；tenant-first 证据查询则通过 `ix_catalog_supply_projection_unit_filter` 的 `Index Only Scan` 将单位放入 `Index Cond`，执行 0.302 ms。所有计划均未关闭 seqscan。

Task 04 文档基线为 p50 **18.144 ms** / p95 **18.736 ms**；本次分别低约 56.1% / 38.7%，未发现回归。该比较只用于本地变化方向，不构成跨环境 SLA；完整 JSON 和逐次时间写入 ignored 的 `target/catalog-search-benchmark/`。

这些结果只描述上述本机、容器配额、数据分布和 warm-cache 查询，不是生产 SLA，也不替代生产容量测试。

## 7. 验证证据

- `InventoryLotTest`：CASE/BOTTLE、null 拒绝、负数、reserved <= on-hand 与同单位 available 不变量。
- `InventoryUnitMigrationIntegrationTest`：fresh V2～V11 + repeatable seed、真实 V9 → V11 ID/行数/旧字段保留、列/约束/PK/索引、非法数据与 seed 单次版本递增证据。
- `InventorySupplyQueryIntegrationTest`：同一 route/pool/SKU 的 CASE/BOTTLE 分组，以及 exact Lot 的 priority/version 和确定性查询顺序。
- `CatalogSearchApiIntegrationTest`：真实 PostgreSQL migration、双单位/filter、游标绑定、401/403、Sales 字段隐藏、tenant/warehouse assignment 与跨单位/跨 SKU 隔离。
- `CatalogSearchPage.test.tsx` / `catalog.test.ts`：生成契约客户端、单位 URL filter、双卡片、Sales 隐藏、exact priority/version、loading/empty/error/403 和本地选择区。
- `catalog-supply-search.live.spec.ts`：真实 OIDC 的 Sales/Warehouse/Buyer/双租户矩阵、单位展示、assignment 边界与零业务写入。
- `ModularityTest` / `ArchitectureRulesTest`：Catalog/Inventory 为根包直接子模块，Inventory 只经 Catalog 公开查询契约组合结果，并自动禁止反向 `Catalog → Inventory` 依赖。
- `catalog_search_benchmark.sh`：空库、确定性双单位规模、主搜索和单位过滤真实索引计划及 p50/p95。

## 8. 已知限制

- Task 07B 只提供读取准备度和合成 seed，不提供 Catalog/Inventory 管理 API、priority 运行时修改、库存调整或移动。
- `sku_supply_projection` 已定义所有权、版本和 staleness 字段，但实时 Inventory 变更与投影消费者将在引入对应写用例时实现；当前 demo 投影由确定性 seed 写入。
- 数量带是非承诺展示，精确批次、priority 和 warehouse version 也只是读取时快照；不执行 CASE/BOTTLE 换算、路线绑定供给决策或确定性分配。最终可分配数量必须由后续原子预占用例重新校验。
- Reservation/Allocation/Movement/Attempt 表、release/consume 和订单 `RESERVED` 迁移均不存在；Inventory reservation 整体状态仍为 Designed。
- 本地 benchmark 不覆盖冷缓存、并发混合负载、生产数据倾斜、跨区域网络或长期写放大。
