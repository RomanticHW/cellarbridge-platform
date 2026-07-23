# ADR-026：同进程、经过认证的只读 MCP 接口

- 状态：Accepted
- 日期：2026-07-23
- 关联：ADR-001、ADR-002、ADR-006、ADR-025

## 背景

CellarBridge 已有经过 OIDC 认证、tenant 隔离并执行 RBAC + ABAC 的应用服务。兼容 MCP
的客户端需要在不复制业务规则、不绕过字段权限、不引入模型运行时的前提下读取当前身份、
供给摘要、工作项、Dashboard、业务时间线和审计证据。

这个入口不是新的业务系统边界。它必须继续依赖现有模块内的 Application Service，并让
JWT、TenantContext、owner/partner/warehouse scope、角色、权限和字段分类在 MCP 调用中保持
与 REST 调用相同的约束。首版只读，也不包含模型 SDK、RAG、向量数据库或通用查询能力。

## 决策驱动

- 复用 Spring Boot 4.1、Spring MVC 与现有 Spring Security Resource Server；
- 使用 MCP 标准协议，避免维护自定义协议分支；
- 每个请求重新认证并建立 TenantContext，不跨请求保存用户身份；
- 保持模块化单体边界，业务 Provider 不直接访问其他模块的 `internal` 包或 Repository；
- 只暴露固定能力白名单，拒绝任意 SQL、任意 URL、任意资源类型和所有写操作；
- 新依赖必须有明确版本、许可证、安全边界和可回滚路径。

## 决策

### 1. 传输与依赖

后端引入 Spring AI MCP Server WebMVC Starter/BOM 2.0.0，并通过它使用传递依赖的官方
Model Context Protocol Java SDK 2.0.0。Spring AI 使用 Apache-2.0 许可证，官方 Java SDK
使用 MIT 许可证。

MCP 与现有后端同进程运行，端点固定为 `/mcp`，传输采用 Spring MVC 的无状态
Streamable HTTP。首版不启用 SSE 兼容端点，也不建立跨请求 MCP session。MCP 服务可通过
`CELLARBRIDGE_MCP_ENABLED` 禁用，默认启用。

Spring AI 在本决策中只提供 MCP server integration，不引入 Chat Model、Embedding Model、
Vector Store、RAG、tool-calling loop 或任何模型供应商 SDK。

### 2. 代码与模块边界

- transport、协议注册、统一 envelope、错误映射和技术安全适配位于 `platform` 内部包；
- 身份、供给以及审计报表 Provider 分别位于所属业务模块内部；
- 每个 Provider 只调用本模块已有 Application Service；
- Provider 不接收 tenant、actor、role、permission、partner 或 warehouse assignment 参数；
- 不增加业务表、Flyway migration、跨模块 SQL 或跨模块 `internal` 依赖。

当前注册且仅注册六个只读 Tool：

1. `cellarbridge_current_user`
2. `cellarbridge_search_supply`
3. `cellarbridge_list_work_items`
4. `cellarbridge_get_dashboard`
5. `cellarbridge_get_timeline`
6. `cellarbridge_search_audit`

当前 discovery 暴露一个固定 Resource 与两个 Resource Template：

1. `cellarbridge://session/me`
2. `cellarbridge://catalog/skus/{skuId}`
3. `cellarbridge://timeline/{subjectType}/{subjectId}`

当前注册三个固定 Prompt：

1. `cellarbridge_daily_operations_brief`
2. `cellarbridge_supply_search_brief`
3. `cellarbridge_trace_business_history`

Tool、Resource 和 Prompt 的输入及输出白名单见
[`10-mcp-capability-contract.md`](../../04-contracts/10-mcp-capability-contract.md)。

### 3. 身份传播与授权

`/mcp` 复用现有 Bearer token 校验和 `cellarbridge-api` audience。每个 HTTP 请求都从已验证
JWT 建立新的 TenantContext，并在请求结束后清理；无状态 transport 不缓存或复用上一个请求
的身份。

MCP 参数和 Prompt 文本不具有授权含义。业务 Provider 继续执行 tenant、permission、role、
owner、partner、warehouse assignment、状态与字段级约束。客户端声明“需要分析”不能提升权限。

当前 Audit/Reporting timeline 对 Quotation/Trade Order/Order 的普通 Sales owner scope 不能仅由其
查询接口完整证明，因此 MCP 对纯 `sales-representative` 读取这些 subject type 采用更严格的
拒绝策略。只有源对象完整授权可被验证的身份才能通过 MCP 读取对应 timeline；后续若增加源模块
owner-scope authorizer，必须先补测试与合同变更，再评估放宽。Buyer 仍固定为 JWT 映射的
partner scope。

### 4. HTTP 与错误安全

- `/mcp` 只接受认证 Bearer 请求；
- Origin/CORS 使用明确白名单，非法 Origin 在调用 Provider 前返回 403；
- 工具统一声明 `readOnlyHint=true`、`destructiveHint=false`、
  `openWorldHint=false`；
- Resource URI 只接受声明的 scheme、路径模板、subject type 和 canonical UUID；
- 每个 Tool 声明与其真实 `data` 一致的严格 JSON Schema 2020-12 `outputSchema`；
- 业务 envelope 使用独立于 MCP protocol 的 `schemaVersion=2.0`，warning 使用稳定
  `code + severity + safeMessage` 对象；
- 四个集合 Tool 使用绑定 tenant/query/授权范围/排序位置/版本/有效期的签名 cursor；
- Reporting freshness 使用 source/checkpoint/pending/dead-letter/rebuild 证据，Supply 明确为
  `OBSERVATION_AGE/UNKNOWN`；
- 默认序列化响应上限为 256 KiB，嵌套集合元素总预算为 1,000；超过预算返回完整的
  `RESULT_TOO_LARGE` 安全错误，不返回截断 JSON；
- 成功输出携带来源、freshness evidence、correlation 和结构化 warning；
- SDK input schema 拒绝采用标准 MCP `isError/text`；业务校验、授权和内部异常使用稳定安全 envelope；
- 响应、日志、Prompt 和 error data 不包含 Authorization header、JWT、portal token、
  cursor secret、idempotency hash、SQL、表名、内部类名、路径、堆栈或原始异常消息；
- 所有 MCP 响应使用 `Cache-Control: no-store`。

当前阶段不实现通用 MCP OAuth discovery、动态客户端注册或独立 authorization server
metadata。它是复用现有 API Bearer token 的受控入口，不得宣传成通用 OAuth MCP 部署。

## 方案比较

| 方案 | 优点 | 代价与风险 | 结论 |
|---|---|---|---|
| Spring AI MCP Server WebMVC Starter 2.0.0 | 与 Boot/WebMVC 配置和生命周期一致；复用官方 Java SDK；声明式注册能力 | 增加 Spring AI MCP 层；需锁定 BOM 并检查传递依赖 | 选择 |
| 官方 Java MCP SDK 2.0.0 直接集成 | 协议控制最直接、依赖层较少 | 需自行连接 WebMVC transport、安全过滤链、序列化与生命周期；重复 starter 已解决的适配 | 保留为 starter 兼容性失败时的备选 |
| 自行实现 JSON-RPC/MCP | 表面依赖最少 | 容易偏离协议、错误语义和后续兼容；安全与 conformance 成本最高 | 拒绝 |
| 独立 sidecar | 进程隔离、可独立扩缩 | 需要新的身份传播和部署边界；容易复制权限或建立跨模块聚合层；运维面扩大 | 首版拒绝 |

## 依赖与安全评估

- Spring AI MCP artifact 使用 Apache-2.0；官方 MCP Java SDK 使用 MIT，与仓库分发边界兼容；
- 版本由 Maven dependency management 锁定，不使用浮动版本；
- 不使用社区 MCP security 模块替代 Spring Security；
- SBOM、依赖树、许可证检查和漏洞扫描仍是发布门禁；出现未接受的阻断漏洞时不发布；
- MCP 客户端、Tool 描述和 Prompt 都视为不可信输入，不能改变服务端权限；
- 同进程意味着 MCP 与 REST 共享 JVM 资源，必须通过输入上限、分页上限、超时和观测防止
  单个客户端无界消耗资源；
- 当前无写工具，所以不建立幂等写入、审批委托或无人值守执行语义。

## 后果

兼容 MCP 的客户端可以通过一个标准入口读取权限过滤后的业务能力，而业务事实与权限仍由
现有应用服务决定。新增 transport 不改变 REST/OpenAPI、AsyncAPI、领域状态机、数据所有权或
数据库 schema。

同进程减少了身份传播和部署复杂度，但 MCP 与主应用共享故障域；运行时必须监测 HTTP
错误、耗时和资源使用。无状态模式简化身份隔离，但客户端不能依赖 server session 保存上下文。

纯 Sales 对 Quotation/Trade Order/Order timeline 暂时比现有页面更严格，这是已知的 fail-closed
差异，不得通过 MCP Provider 自行查询其他模块数据来补齐。

## 回滚

1. 紧急隔离时将 `CELLARBRIDGE_MCP_ENABLED=false` 并拒绝 `/mcp` 路由；
2. 如需完整回滚，部署前一应用版本并移除 MCP starter 与 Provider 注册；
3. MCP 没有数据库 migration、业务写入或持久 session，因此不需要数据回滚；
4. 回滚不影响 REST/OpenAPI、事件消费或读模型；客户端应把端点不可用视为能力撤回，而不是
   重试任何业务写入。
