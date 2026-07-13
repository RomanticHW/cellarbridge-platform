# 酒款与供给检索运行手册

Status: **Available in Task 04**

Requirements: **UC-CAT-001, UC-INV-001, FR-CAT-001–004, FR-INV-001–003**

## 1. 可观察行为

- `/api/v1/catalog/skus` 只返回当前租户的 `ACTIVE` SKU，支持关键词、生产者、产区、国家、分类、年份、容量、供给类型、可用性、预计日期、是否可自动预占和白名单排序组合过滤。
- 英文、中文、重音规范化和常见拼写偏差由 PostgreSQL FTS 与 `pg_trgm` 共同处理；任意 wildcard 和非白名单排序会返回稳定 400。
- 列表采用 HMAC 游标，游标绑定 tenant、全部规范化过滤条件和排序，不能跨租户或跨查询复用。
- 停用 SKU 不进入新增选择列表，但授权用户仍可通过 `/api/v1/catalog/skus/{skuId}` 历史读取。
- 每条供给显示分类、数量带、预计日期、`dataAsOf` 和非承诺免责声明。Sales 不获得精确数量或批次；具有 `inventory:read-exact` 的 Warehouse/Ops 只看到其 warehouse assignment 覆盖的批次，未分配库存池仍保留普通汇总但隐藏精确字段。
- React 工作台将组合过滤写入 URL、对关键词请求执行 300ms debounce、支持游标前后翻页、loading/empty/error/403，并把 SKU 加入仅存在于当前页面内存的“待报价选择区”。该操作不创建报价、订单或库存预占。

## 2. 模型与数据归属

`V4__catalog_products_and_search_projection.sql` 创建 Catalog 自有的 Producer、Region、WineProduct、SKU 和 `sku_supply_projection`。SKU 编码及“product + vintage/NV + volume + units per case + package type”组合在 tenant 内唯一，容量和箱规必须为正数。

`V5__inventory_supply_model.sql` 创建 Inventory 自有的 Warehouse、SupplyPool、Lot 和 warehouse assignment。供给类型固定为：

- `DOMESTIC_ON_HAND`
- `BONDED_ON_HAND`
- `HONG_KONG_ON_HAND`
- `IN_TRANSIT_PRESALE`
- `OVERSEAS_SOURCING`

只有前三类的 generated `automatically_reservable` 为 true。Inventory 的 `sku_id` 是逻辑 Catalog 标识，不建立跨模块外键，也不 join Catalog 表。Catalog 通过公开、tenant-explicit 的 `CatalogSearchQuery` 提供 SKU 与搜索投影；Inventory 应用服务按已批准的 `Inventory → Catalog` 依赖方向编排精确批次和字段权限。搜索投影归 Catalog 所有并显式保存 `data_as_of`，Inventory 仍是精确批次事实来源。

## 3. 搜索与契约

OpenAPI 1.3.0 实现 `/catalog/skus` 和 `/catalog/skus/{skuId}`，并显式声明 400/401/403/404、nullable 精确数量、授权批次、页面 `dataAsOf` 和免责声明。TypeScript 边界由同一契约生成。

搜索索引包括：

- `search_document` 的 GIN FTS 索引；
- `search_text gist_trgm_ops(siglen=64)` 的 GiST trigram 索引；
- tenant/status/dimension B-tree 索引；
- 供给过滤与 pool/SKU 组合索引。

关键词候选由 FTS、整串 trigram 和 word-similarity 三个索引友好分支合并，再应用 tenant、供给条件、稳定排序和页大小。代表性计划实际使用 `ix_catalog_sku_search_trigram` 的普通与 bitmap index scan；不通过 `enable_seqscan=off` 等 planner 开关制造证据。

## 4. 权限与隔离

| 视图 | 必需权限 | 范围与字段 |
|---|---|---|
| Catalog + 供给摘要 | `catalog:read` + `inventory:read` | 当前 tenant；数量带，不含精确数量/批次 |
| 精确库存 | 再加 `inventory:read-exact` | 当前 tenant 且仅 warehouse assignment 覆盖的 supply pool |
| Buyer | 无内部供给访问 | 导航隐藏；直接访问由后端返回 403 |

控制器从不接受 tenant、user 或 warehouse scope 参数。租户和用户来自已验证 token 建立的 `TenantContext`；所有 Catalog、projection、Inventory lot 和 assignment 查询均重复包含 tenant predicate。精确批次按 `(supplyPoolId, skuId)` 聚合，防止同一供给池内其他 SKU 的数量或批次混入。

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

`make catalog-e2e` 使用隔离 Compose project 和新 volume，真实登录 `north.sales`，执行搜索并把 SKU 加入本地待报价选择区，同时验证没有业务写请求；随后真实登录 `north.buyer` 验证导航隐藏和直接访问 403。脚本结束后自动清理。

## 6. 性能证据

本地执行日期：**2026-07-14**。脚本使用独立 PostgreSQL 18.4 容器，从空库应用 V4/V5，确定性写入 **12,000 SKU、36,000 供给投影、36,000 Inventory lot**，执行 `ANALYZE`，热身 5 次后测量 30 次。宿主为 x86_64 macOS/Darwin 25.5.0；Docker Desktop 分配 16 CPU、8,320,479,232 bytes memory。

代表查询为 tenant 内关键词 `starling` + `DOMESTIC_ON_HAND` + `AVAILABLE`，限制 26 行，并执行 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`：

| Metric | Result |
|---|---:|
| Execution p50 | 18.144 ms |
| Execution p95 | 18.736 ms |
| Sample min / max | 17.526 / 19.430 ms |
| Representative execution | 18.410 ms |
| Representative planning | 7.607 ms |
| Top-level shared hit / read blocks | 3,149 / 0 |

计划包含 trigram GiST `Index Scan` 和 `Bitmap Index Scan`、供给投影主键 `Index Scan` 及产品主键 `Index Scan`。12,000 行下 planner 对便宜的精确 FTS 分支和主 SKU 关联仍选择顺序扫描；这是实际 cost-based 计划，已在文档中保留而非隐藏。完整 JSON 和每次时间由脚本写入 ignored 的 `target/catalog-search-benchmark/`，每次执行可重新生成。

这些结果只描述上述本机、容器配额、数据分布和 warm-cache 查询，不是生产 SLA，也不替代生产容量测试。

## 7. 验证证据

- `CatalogDomainTest` / `InventoryLotTest`：NV、正数、状态转换、交易快照准备与数量不变量。
- `CatalogSearchApiIntegrationTest`：真实 PostgreSQL migration、英文/中文/重音/错拼、停用读取、游标绑定、排序/wildcard、401/403、tenant/warehouse/field permission 和跨 SKU 批次隔离。
- `CatalogSearchPage.test.tsx` / `catalog.test.ts`：生成契约客户端、URL/debounce、loading/empty/error/403、Buyer 导航及本地选择区。
- `catalog-supply-search.live.spec.ts`：真实 OIDC、搜索、供给/免责声明、无写入的待报价选择和 Buyer 拒绝。
- `ModularityTest` / `ArchitectureRulesTest`：Catalog/Inventory 为根包直接子模块，Inventory 只经 Catalog 公开查询契约组合结果，并自动禁止反向 `Catalog → Inventory` 依赖。
- `catalog_search_benchmark.sh`：空库、确定性规模、真实索引计划和 p50/p95。

## 8. 已知限制

- 本任务只提供查询与合成 seed，不提供 Catalog 管理 UI/API、库存调整、报价、订单或预占。
- `sku_supply_projection` 已定义所有权、版本和 staleness 字段，但实时 Inventory 变更与投影消费者将在引入对应写用例时实现；当前 demo 投影由确定性 seed 写入。
- 数量带是非承诺展示，精确批次也只是读取时快照；最终可分配数量必须由后续原子预占用例重新校验。
- 本地 benchmark 不覆盖冷缓存、并发混合负载、生产数据倾斜、跨区域网络或长期写放大。
