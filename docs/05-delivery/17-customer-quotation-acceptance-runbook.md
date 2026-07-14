# 客户报价决定运行手册

Status: **Available in Task 06**

Requirements: **UC-QUO-004, FR-QUO-009–011**

## 1. 可观察行为

- 已签发报价生成 256-bit URL-safe capability token；数据库只保存 SHA-256 摘要。访问上下文绑定 tenant、Partner、报价、当前修订、允许动作、条款版本、报价截止时间、token 失效时间与撤销时间。
- 客户页面只返回明确 allow-list：公开供应商/客户身份、SKU 快照、数量、单价、公开费用、总额、币种、付款条款、有效期、公开路线说明和条款摘要。成本、毛利、路线评分/拒绝、内部评论与库存批次不会序列化。
- `SENT` 且 `now < expiresAt` 时可接受或拒绝；接受、拒绝和到期共享同一报价行锁、状态机与唯一客户决定约束，最终状态互斥。
- 接受和拒绝要求 `Idempotency-Key`。相同 key 与相同规范请求返回原决定；相同 key 与不同请求返回 `IDEMPOTENCY_KEY_REUSED`；不同 key 的并发请求仍收敛到同一不可变决定。
- 接受事务原子保存报价状态、`customer_decision`、HTTP 幂等结果、安全审计和完整 `QuotationAcceptedV1` 待发布 envelope。任务不创建订单。
- 到期工作使用 `FOR UPDATE SKIP LOCKED` 批次领取、30 秒 lease 与可重复完成；接受命令仍在事务内即时检查截止时间，不依赖调度及时性。
- React 页面展示有效期、公开金额、条款确认、接受/拒绝动作和决定回执。双击只发出一个命令；刷新和兼容路由只重新读取终态。

## 2. Portal 安全边界

演示 profile 沿用批准的无账号高熵 token 方案，不在本切片扩展 Buyer OIDC。下列请求统一为 404，避免报价枚举：格式错误、未知、撤销、token 已失效，或 tenant/Partner/报价/修订绑定不一致。有效 token 所绑定的报价已到期时，安全 GET 返回 `EXPIRED` 终态；写请求返回 `QUOTE_EXPIRED`。

前端文档包含 `no-referrer` meta；后端返回 `Referrer-Policy: no-referrer` 与 `Cache-Control: no-store`；nginx 对 `/portal/quotes/*`、`/portal/quotations/*` 及 portal API 禁用 access/error token path logging，并设置 CSP、frame denial 与 `nosniff`。专用 Playwright 配置关闭 trace/screenshot，E2E 进程会流式脱敏 stdout/stderr 中的 capability path，失败清理路径仍先扫描服务日志。审计只保存 portal access UUID 和 `CUSTOMER_TOKEN` actor type，不保存原 token、原幂等键或请求体。

公开入口采用两层限流：nginx 按真实边缘来源地址限流，应用按 capability 摘要分别限制读取与决定请求；两层 429 都是不含 token path 的 `application/problem+json`。Compose 暴露的 backend 端口只用于本地诊断，portal 客户流量必须经过 frontend/nginx 入口。

## 3. 数据与事件

`V8__customer_quotation_decisions.sql` 只追加迁移：

- 扩展 `quotation.portal_access` 的 capability 绑定，并把报价截止与 token 失效分离。V7 链接只授予预览且没有冻结决定条款/供应商身份；V8 迁移会将所有此类 legacy link 保持原到期时间、降为 `VIEW` 且撤销，不会静默增加 ACCEPT/REJECT 权限。客户决定必须重新由 V8 应用显式签发链接；
- 新增 append-only `quotation.customer_decision`、只含摘要的 `quotation.http_idempotency` 和带 lease 的 `quotation.expiration_work_item`；
- 新增技术支持 schema `platform_event.event_publication`，以 `PENDING` 保存完整版本化 envelope，无业务模块外键；
- 为接受事实和事件设置唯一约束，数据库层防止重复副作用。

`QuotationAcceptedV1` 包含客户、报价行、金额、账期、路径、条款版本、交付日期/地址与 snapshot hash。幂等摘要位于受控 metadata。Task 07 只从该事实创建订单并实现 consumer Inbox，不回查 Quotation 私有表。

## 4. 本地验证

```bash
make validate
make test
make quotation-e2e
make acceptance-e2e
```

`make acceptance-e2e` 使用隔离 Compose project 启动 PostgreSQL、Keycloak、后端和前端：Sales 创建并提交报价，Manager 独立审批和签发，匿名客户确认条款并双击接受。测试断言安全响应头、只有一个 POST，随后刷新和 `/portal/quotes` alias 均显示相同回执；无论 Playwright 成功或失败，退出处理都会在清理前检查服务日志不存在 token-bearing portal path。

## 5. 自动证据

- `QuotationAggregateTest`：截止边界与接受/拒绝/到期互斥。
- `CustomerQuotationApiIntegrationTest`：真实 PostgreSQL、token 绑定/撤销、锁等待跨截止、幂等重放/冲突、20 路并发、publisher 失败事务回滚、唯一决定/事件、终态 DTO 和到期工作。
- `V8LegacyPortalMigrationIntegrationTest`：真实 V7→V8 升级，证明 legacy preview 被撤销、未延寿且未提升决定权限。
- `PublicQuotationRateLimitFilterTest`：应用 capability 限流与 429 响应不回显 token。
- `QuotationWorkspace.test.tsx`、`quotations.test.ts`：loading、invalid/revoked、expired、accepted/rejected、双击保护、条款确认与 API headers。
- `customer-quotation-acceptance.live.spec.ts`：真实 OIDC 签发、匿名接受、双击、回执、刷新和兼容路由。
- `ModularityTest` / `ArchitectureRulesTest`：模块发现、公开事件边界与禁止 Trade Order 提前依赖/写入。

## 6. 已知限制

- Customer portal 仍使用可撤销高熵 capability token；Task 07 另行增加 Buyer OIDC/Partner mapping，只用于受保护订单资源，不能替代或扩权 portal capability。
- 应用 capability 限流是单实例内存窗口，nginx 来源限流是当前可运行边缘控制；多副本生产环境需要 Task 14 的共享限流/WAF 与受限 backend ingress，不能把本地诊断端口作为公开入口。
- `PENDING` publication 已与接受事务原子持久化；Task 07 已实现本地订单消费、Inbox、有界失败重试和唯一订单。`PENDING` 继续表示外部 broker 尚未确认，本地消费不会把它改成 `PUBLISHED`。
- 本任务按范围只可靠发布 `QuotationAcceptedV1`；拒绝和到期保存终态与安全审计，不创建订单或外部通知。
- 公开路线日期是签发修订冻结的请求交付日期，不代表承运商实时承诺。
