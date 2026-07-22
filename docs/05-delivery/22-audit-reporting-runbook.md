# 审计与经营报表运行手册

## 1. 当前边界

Task 12 把可靠业务事件最终一致投影为不可变审计、统一业务时间线、个人/团队工作队列和经营指标。
读模型不参与报价、预占、履约或结算决策，也不引入数据仓库、Elasticsearch 或外部 BI。

当前 Audit/Reporting 纵向切片为 **Available**。

## 2. 投影与一致性

- 每个事件以 `(tenant, projectorName, eventId)` 去重，并持久化 payload binding、checkpoint 和计数。
- 同一 subject 的状态按 business version、occurredAt、eventId 防止旧事件覆盖新状态。
- 缺少 OPEN 先决事件的完成/关闭进入 `PENDING`；先决事件到达后协调完成，不伪造工作项。
- 页面显示 `dataAsOf`、lag 与 `CURRENT/STALE/REBUILDING/EMPTY`，详情源 API 仍是业务真相。
- 投影异常可从 `audit_reporting.projector_inbox` 与 `projector_checkpoint` 检查；
  `PENDING/DEAD_LETTER` 不能当作已处理成功。

## 3. API 与页面

- `GET /api/v1/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD`：UTC 范围、角色过滤指标及图表序列。
- `GET /api/v1/audit/entries`：subject、correlation、actor、action 与时间过滤，不透明游标分页。
- `GET /api/v1/timeline`：按 Partner/Quotation/Order 返回安全统一时间线。
- `GET /api/v1/work-items`：状态、优先级、类型、到期日和业务编号过滤；支持 personal/team scope。
- `/app/dashboard` 提供 cards、ECharts、tooltip、ARIA/decal 和表格 fallback。
- `/app/audit` 与 `/app/work-items` 分别提供授权审计搜索和工作队列；统一时间线嵌入源详情页。

所有响应使用 `Cache-Control: no-store`。Dashboard 最大日期范围为 367 个 UTC 日期，分页上限为 100。

## 4. 权限与字段边界

- Dashboard/Work queue 要求 `reporting:read`；团队队列另限 Sales Manager/Tenant Administrator。
- Audit search 要求 `audit:read`；Sales Representative 固定为本人 actor 范围。
- Timeline 复用 Partner/Quotation/Order read 权限；Buyer 继续受身份映射 partner 限制。
- Finance 只获得应收状态指标；System Operator 只获得技术敏感分类，不因此读取商业数据。
- 所有 SQL 先限定 tenant；安全摘要不包含 token、地址、成本、毛利、付款参考号或原始 payload。

## 5. 重建与恢复

重建由受 `administration:manage-access` 保护的应用服务执行：

1. 在同租户创建 `STAGING` generation；
2. 从 `platform_event.event_publication` 按 `(occurredAt,eventId)` 重放；
3. 对不支持的事件跳过，对失败 generation 标记 `FAILED`；
4. 校验事件计数和 `dataAsOf` 后，在单事务内退役旧 generation、激活新 generation；
5. 增量读取在整个过程中继续使用旧 `ACTIVE` generation。

当前没有公开的重建 HTTP 端点；生产运维入口和审批流程属于后续发布加固范围。不得手工 truncate
active 表或修改 `audit_entry`。

## 6. 数据与契约兼容性

- V21 只新增 `audit_reporting` schema，不改写 V2～V20，也不建立跨模块 FK。
- OpenAPI 1.12 增量实现四类读端点；AsyncAPI 1.5 增加投影所需的既有生产事实。
- 审计使用数据库 trigger 拒绝 update/delete；timeline/work/metric 可按 generation 重建。
- ECharts 采用模块化依赖；图表数据同时提供可展开表格，不以颜色作为唯一语义。

## 7. 验证入口

```bash
./mvnw -pl backend -am verify
pnpm --dir frontend test:coverage
pnpm --dir frontend build
make reporting-e2e
python3 scripts/validate_repository.py --scope all
```

核心证据包括 `AuditReportingIntegrationTest`、`ReportingWorkspace.test.tsx` 和真实
OIDC/PostgreSQL/React 的 `audit-reporting.live.spec.ts`；集成测试覆盖重复/乱序/pending、重建等价、
双租户/角色/UTC 边界和代表查询计划。
