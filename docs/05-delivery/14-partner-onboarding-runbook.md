# 商业客户准入与审核运行手册

Status: **Available in Task 03**

Requirements: **UC-PAR-001/002, FR-PAR-001–006**

## 1. 可观察行为

- Sales 可创建不完整的 `DRAFT`，但提交前必须补齐法定名称、登记识别号、类型、主联系人、账单地址、默认币种、付款条款、路径、区域和币种。
- 同租户登记识别号由规范化值和数据库唯一索引共同阻断；规范化法定名称命中时必须提供差异说明才能提交。
- 状态机为 `DRAFT` / `CHANGES_REQUESTED` → `PENDING_REVIEW` → `ACTIVE` / `CHANGES_REQUESTED` / `REJECTED`，`ACTIVE` 可暂停，`SUSPENDED` 可申请重新审核。
- 提交人与审核人必须不同；此规则对同时拥有提交和审核权限的 Tenant Admin 同样生效，没有默认豁免。
- 批准会新增不可变资格版本，保存路径、区域、币种、账期和可选信用额度。后续重新激活产生新版本，不覆盖旧交易应使用的历史快照。
- React 工作区提供列表、关键字/状态/负责人/批准路径/更新时间筛选、稳定游标分页、创建、编辑、详情、审核、资格和审计时间线，并显式处理 loading、empty、403、409、412、422 与未保存修改。

## 2. API 与并发契约

实现的入口为 `/api/v1/partners`、`/{partnerId}`、`/submission`、`/review`、`/suspension` 和 `/reactivation`。所有入口需要经过验证的 bearer token；租户只来自服务端建立的 `TenantContext`。

更新和状态命令必须携带强格式 `If-Match: "<version>"`。缺失时返回 `428 PRECONDITION_REQUIRED`，版本过期返回 `412 RESOURCE_VERSION_CONFLICT` 并带当前版本/状态，客户端必须重新加载，不静默覆盖。

列表游标用服务端 HMAC 绑定租户与规范化 filter。tenant predicate 在关键字、状态、owner、路径、更新时间、排序和分页之前执行；跨租户 ID 猜测返回相同的 tenant-safe 404。

## 3. 权限与所有权

| 能力 | 稳定权限码 | 额外约束 |
|---|---|---|
| 查看列表/详情 | `partner:read` | 当前租户 |
| 新建/编辑 | `partner:create` | Sales 只能编辑本人草稿；reviewer 可处理租户内草稿 |
| 提交/申请重新激活 | `partner:submit` | 本人或 reviewer；状态必须允许 |
| 审核/暂停 | `partner:review` | 审核人与最近提交人不同 |

控制器不根据显示角色名称做决定；应用服务通过 `AuthorizationService` 检查权限和目标 tenant，再检查 owner 与状态。

## 4. 数据、审计与事件

`V3__partner_onboarding.sql` 创建模块自有 `partner` schema：聚合、资格版本、不可变审核决定、审计时间线、最小审核待办和本地事件 publication。没有跨模块数据库外键。

下列事实与业务写入在同一 PostgreSQL 事务中持久化：

- `PartnerSubmittedForReviewV1`
- `PartnerActivatedV1`
- `PartnerChangesRequestedV1`
- `PartnerRejectedV1`
- `PartnerSuspendedV1`

Task 03 不启用 Kafka。提交同步创建最小 review work item；审核决定完成所有开放待办。联系人/地址变更只在普通审计中记录字段名，日志不写敏感原值；API 只返回登记识别号掩码。

## 5. 本地演示

这些账号只存在于 `demo` profile，密码均为 `CellarBridge-Demo-2026!`：

| Username | Role | Partner behavior |
|---|---|---|
| `north.sales` | Sales Representative | 创建、编辑本人草稿并提交 |
| `north.manager` | Sales Manager | 独立审核、激活或暂停 |
| `north.admin` | Tenant Administrator | 可提交和审核，但不能审核自己的提交 |
| `harbor.manager` | Sales Manager | 独立 Harbor tenant，用于隔离验证 |

```bash
make dev-core
# open http://localhost:5173/app

make partner-e2e
```

`make partner-e2e` 使用隔离 Compose project 和全新 volume，真实登录 `north.sales` 创建并提交客户，由 `north.manager` 激活，再让 Sales 看到 `ACTIVE`；第二条链路由 `north.admin` 提交后尝试自审并验证稳定 409。脚本结束后自动清理容器和数据。

## 6. 前端依赖决策

伙伴编辑和审核包含嵌套字段、数组、条件字段与客户端/服务端错误映射。曾评估手工 `useState`：依赖更少，但字段触达、重置、异步默认值和条件校验会散落在页面中；Formik 增加相近依赖面且没有为本切片提供更明确收益。因此采用 React Hook Form 管理受控表单生命周期，以 Zod 作为唯一客户端 schema，并用 resolver 连接两者。

| Dependency | Version | License | Purpose |
|---|---:|---|---|
| [`react-hook-form`](https://www.npmjs.com/package/react-hook-form) | 7.81.0 | MIT | 表单状态、dirty guard、异步 reset 与提交 |
| [`zod`](https://www.npmjs.com/package/zod) | 4.4.3 | MIT | 可组合、类型安全的客户端输入 schema |
| [`@hookform/resolvers`](https://www.npmjs.com/package/%40hookform/resolvers) | 5.4.0 | MIT | Zod 与 React Hook Form 的薄适配层 |

版本和传递依赖由 `pnpm-lock.yaml` 精确锁定；CI 使用 frozen lockfile 并执行依赖审计。所有 Ant Design 输入通过 `Controller` 接入，避免非原生组件的受控值丢失。回滚时可逐页替换为现有 React 受控状态，不影响 OpenAPI 或后端契约；升级时必须重跑表单单测、412/422 映射和真实浏览器审核链路。

## 7. 验证证据

- `PartnerTest`：状态转换、完整性、重复提示、双人原则、暂停/重新激活和资格不变量。
- `PartnerApiIntegrationTest`：PostgreSQL 空库 migration、成功流程/持久化、401/403/404/409/412/422、ownership、双租户查询和 cursor/filter 绑定。
- `PartnerWorkspace.test.tsx`：列表/空态/403、创建、更新冲突、提交错误、自审冲突与成功审核。
- `partner-onboarding.live.spec.ts`：真实 OIDC、React 表单、跨身份审核、资格展示和自审拒绝。
- `ModularityTest` / `ArchitectureRulesTest`：Partner 仍是 `com.rom.cellarbridge` 的直接子模块，公开跨模块入口仅为 `PartnerEligibilityService` 与稳定状态类型。

## 8. 已知限制

- 本地 publication 已持久化，但外部 Kafka adapter、重试控制台和通用 workflow engine 不在本任务范围。
- 审核待办是 Partner 模块内部最小能力，没有独立 Notification 收件箱页面。
- 不上传证照，不提供真实信用评分、KYC 或法律合规结论；信用额度只是审核人输入的演示资格字段。
- OpenAPI 是公开同步接口的权威契约；事件 payload 的机器契约将在启用跨模块消费者前继续细化。
