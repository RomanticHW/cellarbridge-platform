# 性能、并发与扩展设计

## 1. 性能原则

- 先定义容量和正确性，再优化；
- 数据库约束和 SQL 计划是核心证据；
- 缓存不承担交易正确性；
- 避免虚构百万 QPS；
- 每个优化有基准、可逆性和一致性说明。

## 2. 关键热点

1. 商品/SKU/供给搜索；
2. 报价详情和金额计算；
3. 热点 SKU 库存预占；
4. 事件发布/消费积压；
5. 履约工作队列；
6. 审计/时间线和驾驶舱读模型。

## 3. 商品搜索

使用 PostgreSQL：

- 标准化搜索列；
- `tsvector` 全文索引用于名称/生产者/产区/标签；
- `pg_trgm` 用于模糊匹配；
- 结构化过滤使用 B-tree/GIN；
- keyset/稳定分页；
- availability 通过受控 read model/批量查询，不逐行 N+1；
- 查询计划保存到性能报告。

预计 15,000 SKU 级别无需独立 Elasticsearch。若数据/查询证据显示不足，再 ADR。

## 4. 库存并发

### 威胁

两个或更多订单同时读取相同 available，均尝试预占导致超卖。

### 方案

- 确定性候选顺序减少死锁；
- 单个批次用条件 UPDATE；
- 所有订单行一个事务；
- 受影响行数判断；
- 唯一 order reservation 防重复；
- PostgreSQL deadlock/serialization 错误仅有限重试；
- 不使用 JVM synchronized、Redis lock 或先读后写作为最终保护。

### 并发测试

- 100~500 并发命令争抢同一批次；
- 多批次、多行相反顺序，验证稳定排序避免死锁；
- 进程重试/重复事件；
- 故意在中间行失败验证全量回滚；
- 断言数据库方程和 movement 守恒。

## 5. 幂等订单

- `trade_order(tenant_id, source_quotation_id)` unique；
- 请求/事件先查优化，但唯一冲突为最终防线；
- 并发插入冲突后读取既有订单并验证 snapshot hash；
- 不一致 hash 触发严重契约错误，不返回错误订单；
- idempotency result 有保留策略。

## 6. 缓存

可缓存：

- 路径/审批策略版本只读配置；
- 商品展示详情；
- 低风险参考数据；
- JWK（由安全库控制）；
- dashboard 短 TTL 结果（明确更新时间）。

不缓存为事实：

- 库存预占判断；
- 报价当前状态；
- 订单创建幂等结果的唯一防线；
- 应收余额写入决定；
- 权限撤销的长期结果。

Redis 不可用时 core 正确性继续，性能下降并记录指标。

## 7. 工作队列与调度

- DB 队列领取用 `FOR UPDATE SKIP LOCKED`；
- 批次大小、租约、最大执行时间；
- 多实例安全；
- 避免全表扫描，索引 `(status, due_at, id)`；
- 处理慢任务拆异步，不持有长事务；
- backlog 和 oldest age 指标。

## 8. 读模型

驾驶舱/时间线通过事件投影：

- 源事件幂等；
- 增量更新；
- checkpoint；
- 可清空重建；
- 页面显示 lastUpdated/projectionLag；
- 不在用户请求中跨 10 个 schema join。

## 9. 横向扩展

模块化单体可运行多实例：

- 无本地业务会话；
- 数据库唯一/锁保护；
- 调度任务分布式领取；
- 事件消费者 group；
- 上传/附件不存本地磁盘（P1 可只保存元数据）；
- cache shared 但非正确性依赖。

不声称线性扩展；发布前以 1~3 实例演示测试。

## 10. 性能报告

每次报告包含：commit、环境、CPU/RAM、容器限制、数据规模、脚本、warm-up、持续时间、P50/P95/P99、吞吐、错误、DB plan、GC 和结论。只发布可重现结果。

Task 14 已提供 `smoke`（目标 10 分钟）和 `full`（目标 30 分钟）两个版本化 profile。确定性生成器、正确性绑定的 Maven/PostgreSQL harness、CI smoke 与人工 full workflow 见 `docs/05-delivery/24-performance-resilience-runbook.md`；结果保存在 ignored 的 `target/performance-evidence/`，公开结论见 `docs/evidence/performance/report.md`。
