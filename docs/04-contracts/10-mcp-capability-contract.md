# MCP 能力合同

## 1. 范围与版本

本合同定义 CellarBridge `/mcp` 的当前只读能力。它是独立于 REST/OpenAPI 的 MCP
JSON-RPC 接口，复用同一后端的认证、TenantContext 和应用服务，不改变领域状态机、数据库
schema、OpenAPI 或 AsyncAPI。

当前业务 envelope 的 `schemaVersion` 为 `2.0`。`1.0 → 2.0` 是明确的 major 变化：
`warnings` 从字符串数组升级为对象数组，并新增必需但可为 `null` 的 `freshness`。
删除字段、改变字段类型、放宽授权语义或修改稳定能力名称必须再次提升业务 schema major，
并更新本合同。MCP protocol negotiation 与业务 envelope 版本分别管理。

## 2. Transport 与请求边界

- Endpoint：`/mcp`
- Transport：无状态 Streamable HTTP
- Framing：JSON-RPC 2.0
- 认证：`Authorization: Bearer <access-token>`
- Audience：首版复用 `cellarbridge-api`
- 请求媒体类型：`application/json`
- 响应协商：`application/json, text/event-stream`
- Origin：浏览器请求必须发送；请求携带 Origin 时必须匹配部署白名单，非法 Origin 返回 403
- 缓存：`Cache-Control: no-store`

除 initialize 协商外，客户端发送当前协商的 `MCP-Protocol-Version`。服务端不要求或返回可供
后续身份复用的业务 session；每个请求重新校验 token、建立 TenantContext，并在请求结束后
清理。

客户端不能通过 query、JSON-RPC params、Tool arguments、Resource URI 或 Prompt arguments
传入 `tenantId`、`actorId`、角色、权限、partner scope、warehouse assignment、Authorization、
JWT、portal token 或 cursor secret。未知字段必须拒绝，不能静默解释为筛选或授权指令。

## 3. 成功 envelope

所有只读 Tool 的 `structuredContent`、text content JSON 与 JSON Resource 使用下列统一字段；
Tool 成功时协议级和 envelope 的 `isError` 均为 `false`，两种 content 表示必须相同：

| 字段 | 合同 |
|---|---|
| `schemaVersion` | 业务 envelope 版本，当前固定 `2.0` |
| `sourceKind` | 固定来源类型，见下表 |
| `dataAsOf` | 来源最近安全时间，RFC 3339 UTC；没有可证明来源时间时为 `null` |
| `projectionStatus` | `CURRENT`、`STALE`、`REBUILDING`、`UNKNOWN`、`EMPTY` 或仅 SESSION 可用的 `NOT_APPLICABLE` |
| `freshness` | 可机器判断的 freshness evidence；SESSION/错误为 `null`，最终一致读取按下节返回完整对象 |
| `correlationId` | 当前请求的安全关联标识，不等于 token、trace header 或业务授权 |
| `warnings` | `{code,severity,safeMessage}` 对象数组；`severity` 仅 `INFO`/`WARNING`，客户端只按稳定 code 分支 |
| `data` | 当前 Tool/Resource 的权限过滤结果 |
| `isError` | 成功固定为 `false`，安全失败为 `true` |
| `code` / `safeMessage` | 成功为 `null`；失败时为稳定错误码和安全消息 |
| `retryable` | 成功固定为 `false`；失败时只按稳定错误分类决定 |

当前 `sourceKind`：

| 来源 | 使用范围 | `projectionStatus` |
|---|---|---|
| `SESSION` | 当前身份与租户上下文 | 固定 `NOT_APPLICABLE`；`dataAsOf=null` |
| `SUPPLY_PROJECTION` | SKU 与供给搜索/详情 | 有结果为 `UNKNOWN`，无结果为 `EMPTY` |
| `WORK_ITEM_PROJECTION` | 工作队列 | `CURRENT`、`STALE`、`REBUILDING`、`UNKNOWN` 或 `EMPTY` |
| `DASHBOARD_PROJECTION` | 经营 Dashboard | `CURRENT`、`STALE`、`REBUILDING`、`UNKNOWN` 或 `EMPTY` |
| `TIMELINE_PROJECTION` | 统一业务时间线 | `CURRENT`、`STALE`、`REBUILDING`、`UNKNOWN` 或 `EMPTY` |
| `AUDIT_PROJECTION` | 不可变审计查询 | `CURRENT`、`STALE`、`REBUILDING`、`UNKNOWN` 或 `EMPTY` |

`NOT_APPLICABLE` 禁止用于任何最终一致读模型。`REBUILDING` 必须原样返回并携带
`PROJECTION_REBUILDING`。仅当 source、checkpoint 与 read model 均无证据时 freshness 才为
`EMPTY`；source 已存在但没有 active generation 为 `STALE`。业务结果为空仍不证明其他
tenant 或其他权限范围内是否存在对象。

### 3.1 Freshness evidence

`freshness` 固定字段如下；所有时间均为 RFC 3339 UTC，计数和秒数必须非负，无法证明的值为
`null`，不得伪造为 `0`：

| 字段 | 合同 |
|---|---|
| `mode` | `SOURCE_WATERMARK` 或 `OBSERVATION_AGE` |
| `observedAt` | 服务端生成 evidence 的时间 |
| `sourceWatermark` | 当前 Reporting projector 支持事件类型的最高提交时间；不可证明时为 `null` |
| `projectorWatermark` | Reporting 持久化的 last-handled checkpoint 时间；pending/dead-letter 由独立计数与 warning 表达；Supply 为 `null` |
| `lastSuccessfulRefreshAt` | 最近一次可证明的成功投影/观测时间 |
| `lagSeconds` | `sourceWatermark - projectorWatermark` 的非负差；OBSERVATION_AGE 为 `observedAt - dataAsOf` |
| `pendingCount` | 当前 tenant/projector 的 pending 数；不可证明时为 `null` |
| `deadLetterCount` | 当前 tenant/projector 的 dead-letter 数；不可证明时为 `null` |

Reporting 使用 `SOURCE_WATERMARK`。只有 source watermark 与 checkpoint 对齐、pending/dead
letter 都为零且没有 staging rebuild，才可返回 `CURRENT`；因此一个长期没有新事件的安静系统
仍可为 `CURRENT`。source 领先、pending、dead letter 或缺失必要 checkpoint 时返回 `STALE`
及对应 warning；staging generation 返回 `REBUILDING`。

Supply 使用 `OBSERVATION_AGE`。当前 Catalog/Inventory 查询可以给出业务行的观测时间，却
没有 source-to-projection checkpoint，因此有结果时固定为 `UNKNOWN`，并携带
`FRESHNESS_NOT_SOURCE_VERIFIED`；不得用“最近一行小于十秒”推断投影健康。

稳定 warning code：

| Code | Severity | 触发 |
|---|---|---|
| `AVAILABILITY_INFORMATIONAL` | `INFO` | 供给只是信息摘要，预占时必须重新校验 |
| `FRESHNESS_NOT_SOURCE_VERIFIED` | `WARNING` | 只能观测年龄，不能证明 source 对齐 |
| `PROJECTION_PENDING` | `WARNING` | projector 有未完成 source event |
| `PROJECTION_DEAD_LETTER` | `WARNING` | projector 有需运维处理的失败事件 |
| `PROJECTION_REBUILDING` | `WARNING` | staging generation 正在重建 |
| `PROJECTION_STALE` | `WARNING` | source watermark 领先或 checkpoint 证据不足 |
| `PROJECTION_FRESHNESS_UNKNOWN` | `WARNING` | 现有证据互相矛盾，无法证明 freshness |

## 4. 安全错误 envelope

Tool 业务失败同时设置协议级 `CallToolResult.isError=true` 和返回 envelope 内
`isError=true`，envelope 同步出现在 `structuredContent` 与 text content JSON。Resource 业务
失败返回包含同一 envelope 的 JSON content。JSON-RPC parse、invalid request、method not found
或尚未进入 Provider 的认证/协议错误仍使用 HTTP/JSON-RPC 标准错误语义。

```json
{"schemaVersion":"2.0","sourceKind":"TIMELINE_PROJECTION","dataAsOf":null,"projectionStatus":null,"freshness":null,"correlationId":"00000000-0000-0000-0000-000000000000","warnings":[],"data":null,"isError":true,"code":"ACCESS_DENIED","retryable":false,"safeMessage":"The authenticated user is not allowed to perform this operation."}
```

允许复用的稳定码包括 `VALIDATION_FAILED`、`MALFORMED_REQUEST`、
`AUTHENTICATION_REQUIRED`、`INVALID_ACCESS_TOKEN`、`ACCESS_DENIED`,
`RESOURCE_NOT_FOUND`、`CURSOR_INVALID`、`RESULT_TOO_LARGE`、
`DEPENDENCY_UNAVAILABLE` 和 `INTERNAL_ERROR`。`CURSOR_INVALID` 覆盖畸形、签名错误、过期、
跨 tenant、跨 capability、跨规范化查询或跨授权范围，不指出具体失败原因，也不回显 cursor。
`RESULT_TOO_LARGE` 要求客户端缩小 page 或筛选条件，固定 `retryable=false`。认证失败仍由
HTTP 401 表达，非法 Origin 仍由 HTTP 403 表达；JSON-RPC parse、invalid request、method not
found 等协议错误保留标准 JSON-RPC code。

`safeMessage` 不返回 SQL、表名、内部类/包名、文件路径、堆栈、原始异常、token、hash、
其他 tenant 标识或对象存在性。相同 tenant 但无权限和其他 tenant 的猜测 UUID 均采用安全的
拒绝/不存在语义。

## 5. Tool 清单与输入白名单

六个 Tool 均必须声明：

```text
readOnlyHint=true; destructiveHint=false; openWorldHint=false
```

| Tool | 允许的 arguments | 授权与输出边界 |
|---|---|---|
| `cellarbridge_current_user` | 无 | 任意有效内部/Buyer token；返回安全身份、tenant、partner binding、角色和权限，不返回 token/hash |
| `cellarbridge_search_supply` | `keyword`, `producer`, `region`, `countryCode`, `category`, `vintage`, `volumeMl`, `supplyTypes`, `availabilityClasses`, `quantityUnits`, `automaticallyReservable`, `availableFrom`, `availableTo`, `sort`, `pageSize`, `cursor` | 要求 `catalog:read` + `inventory:read`；精确 lot 另要求 `inventory:read-exact` 且受 warehouse assignment；Buyer 拒绝 |
| `cellarbridge_list_work_items` | `statuses`, `priorities`, `types`, `dueFrom`, `dueTo`, `subjectNumber`, `scope`, `pageSize`, `cursor` | 要求 `reporting:read`；`scope` 仅 `personal`/`team`，默认 personal；team 仅 Sales Manager/Tenant Administrator |
| `cellarbridge_get_dashboard` | `from`, `to` | 要求 `reporting:read`；UTC 日期且最多 367 日；Sales/Finance/System Operator 保持原角色投影 |
| `cellarbridge_get_timeline` | `subjectType`, `subjectId`, `pageSize`, `cursor` | `subjectType` 仅 `PARTNER`、`QUOTATION`、`TRADE_ORDER`、`ORDER`；UUID canonical；纯 Sales Representative 读取 Quotation/Trade Order/Order 时 fail closed；Buyer 固定 partner scope |
| `cellarbridge_search_audit` | `subjectType`, `subjectId`, `correlationId`, `action`, `from`, `to`, `pageSize`, `cursor` | 要求 `audit:read`；客户端不能指定 actor，Sales actor scope 固定本人；System Operator 只获得技术分类；Buyer 拒绝 |

通用输入规则：

- `pageSize` 取值 1～100，省略时使用服务端默认值；
- 时间为 RFC 3339 UTC，Dashboard 日期为 `YYYY-MM-DD`；
- UUID 使用小写 canonical 表示；
- 供给类型仅
  `DOMESTIC_ON_HAND`、`BONDED_ON_HAND`、`HONG_KONG_ON_HAND`、
  `IN_TRANSIT_PRESALE`、`OVERSEAS_SOURCING`；
- availability class 仅 `AVAILABLE`、`LIMITED`、`UNAVAILABLE`、
  `REQUIRES_CONFIRMATION`；quantity unit 仅 `CASE`、`BOTTLE`；
- category 仅 `RED`、`WHITE`、`ROSE`、`SPARKLING`、`FORTIFIED`、`DESSERT`、
  `OTHER`；country code 仅 ISO 3166-1 alpha-2 大写形式；vintage 仅 `NV` 或
  1900～2099 四位年份；
- 供给排序仅 `relevance`、`name`、`-updatedAt`、`vintage`；
- 工作项 status 仅 `OPEN`、`CLAIMED`、`COMPLETED`、`CANCELLED`，priority 仅
  `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`，type 仅 `PARTNER_REVIEW`、
  `QUOTATION_APPROVAL`、`FULFILLMENT_STEP`、`EXCEPTION_ACTION`、
  `RECEIVABLE_FOLLOW_UP`；
- 其他 enum、subject type 和筛选同样使用服务端白名单；
- cursor 不透明并绑定 tenant 和查询上下文，不能修改、记录、放入 Resource URI 或跨查询复用；
- Audit 默认查询截至当前时间的最近 30 日，最大 367 日；Dashboard 是最多 367 个 UTC 日期；
- Supply `availableFrom <= availableTo`；Work/Audit 的结束时间 exclusive 且起点必须早于终点；
  Dashboard 两端日期 inclusive 且 `from <= to`；服务端拒绝超过各能力上限的范围；
- 集合输入去重，空白字符串按合同校验，不扩展成 wildcard；
- Tool 不接受任意 SQL、字段选择、include/expand、URL、文件路径或回调地址。

`cellarbridge_search_supply` 的返回始终保留“供给为信息摘要、最终分配在预占时重新校验”的
warning。无 `inventory:read-exact` 时，精确数量、lot、warehouse priority/version 必须缺失或
为空，不能通过 Prompt 或后续 Tool 调用推导。

### 5.1 每个 Tool 的严格 `data` schema

六个 Tool 的 `outputSchema` 都是“第 3 节 Envelope 2.0 + 专属 `data`”。所有对象
`additionalProperties=false`；字段类型、enum、nullable、数组 item/上限由
`tools/list.outputSchema` 完整声明并以真实结果做 Draft 2020-12 校验，不能退回自由 Map。
集合 Tool 的 `PageInfo` 固定为 `nextCursor:string|null`、`hasNext:boolean`、
`pageSize:integer[1,100]`；只有 `hasNext=true` 时 cursor 非空。schema 不扩大既有字段权限。
SDK 在 Provider 前拒绝 input schema 时返回标准 MCP `isError/text`；进入 Provider 后的业务
失败才使用带稳定 `code` 的安全 envelope，两类结果都不得回显原始参数或内部信息。

### 5.2 Cursor 与结果预算

四个集合 Tool 的 cursor 均使用 HMAC-SHA256 并以 constant-time 校验，默认 TTL 为 15 分钟。
cursor 至少绑定 tenant、capability、schema/cursor version、规范化 filter/sort、调用者身份或
关键权限和最后稳定排序位置。Supply cursor 绑定 user 与 exact-read 权限，并在每页重新校验
当前 warehouse assignment；assignment 变化会立即改变可见结果但不单独作废 cursor。Supply TTL
由 `CELLARBRIDGE_CATALOG_CURSOR_TTL` 配置，Reporting 三个
集合 Tool 的 TTL 由 `CELLARBRIDGE_AUDIT_CURSOR_TTL` 配置。排序固定为：

- supply：已声明 sort + 唯一 SKU tie-breaker；
- work items：`dueAt ASC NULLS LAST, priority DESC, id ASC`；
- timeline：`occurredAt DESC, sourceEventId DESC`；
- audit：`occurredAt DESC, id DESC`。

客户端只能把 Tool 返回的 cursor 原样交回同一个 Tool、同一组规范化 arguments 和同一授权
上下文。篡改、过期、跨 tenant、跨查询、跨 capability 或已绑定权限变化返回 `CURSOR_INVALID`。
cursor 不得进入日志、trace、Resource URI、Prompt、截图或证据。

默认 `pageSize` 上限为 100，完整 envelope 的 UTF-8 预算为 262,144 bytes（256 KiB），嵌套
数组累计预算为 1,000。部署可向下收紧 page/集合预算；response bytes 可在 1 KiB 至 4 MiB
内配置，变量分别为 `CELLARBRIDGE_MCP_MAX_PAGE_SIZE`、`CELLARBRIDGE_MCP_MAX_RESPONSE_BYTES`
和 `CELLARBRIDGE_MCP_MAX_COLLECTION_ITEMS`。
超限时整个结果替换为 `RESULT_TOO_LARGE`，不返回部分 `data`。

## 6. Resource 清单

| URI / Template | 输入白名单 | 授权与内容 |
|---|---|---|
| `cellarbridge://session/me` | 无 | 与 `cellarbridge_current_user` 相同的 SESSION envelope |
| `cellarbridge://catalog/skus/{skuId}` | 单个 canonical UUID | 与供给详情应用服务相同；要求 Catalog + Inventory read；字段按 exact 权限过滤 |
| `cellarbridge://timeline/{subjectType}/{subjectId}` | subject type 仅 `PARTNER`、`QUOTATION`、`TRADE_ORDER`、`ORDER`；单个 canonical UUID | 与 timeline Tool 相同的源对象权限、Buyer partner scope，以及纯 Sales 对 Quotation/Trade Order/Order 的拒绝；返回默认首屏和 `pageInfo` |

每次 `resources/read` 都重新授权。URI 不得包含 tenant、actor、token、付款参考、cursor、签名
URL 或任意 query。Timeline Resource 若 `pageInfo.hasNext=true`，客户端必须改用
`cellarbridge_get_timeline` 并传回 `pageInfo.nextCursor` 读取后续页；Resource URI 不能承载
cursor。`resources/list`/`resources/templates/list` 只描述能力，不返回业务记录。

## 7. Prompt 清单

| Prompt | 首版参数 | 合同 |
|---|---|---|
| `cellarbridge_daily_operations_brief` | 无 | 说明如何读取当前身份、个人/授权团队工作项和 Dashboard，组织每日工作简报 |
| `cellarbridge_supply_search_brief` | 无 | 说明供给检索输入、字段权限和非承诺边界，建议调用供给 Tool/Resource |
| `cellarbridge_trace_business_history` | 无 | 说明如何先确认 subject type/UUID，再读取 timeline 和授权审计证据 |

Prompt 只返回公开的任务步骤、输入说明和建议 Tool。它不执行 Tool，不嵌入业务记录、数据库
文本、隐藏权限、客户数据或认证信息，也不指示客户端调用任何写操作。Prompt 文本不能保证调用
成功，服务端仍在每次 Tool/Resource 请求中重新授权。

## 8. 明确不提供

- 合作方、报价、库存、履约、异常、付款、冲正、权限或 Projection rebuild 写操作；
- 任意 SQL、Repository、文件系统、shell、网络抓取或动态 Tool 注册；
- 自动审批、无人值守业务执行或跨请求授权委托；
- 内置模型、模型路由、RAG、向量数据库或对话记忆；
- 完整 MCP OAuth discovery、动态客户端注册或通用第三方 authorization server；
- 将最终一致投影表示为交易源事实。

## 9. 验收合同

实现必须覆盖 initialize、Tool/Resource/Prompt discovery 和读取，逐个验证六个 Tool 的严格
`outputSchema` 与真实成功结果，并验证公共业务 error envelope 和协议 input 拒绝代表场景；
验证四个集合 Tool 的首/中/末页、同值 tie-breaker、
HMAC/TTL、篡改/跨查询/跨 tenant/跨权限 cursor；验证 256 KiB/1,000 元素预算；
验证安静同步、source 领先、pending、dead letter、rebuild、无 generation 与 Supply
`OBSERVATION_AGE/UNKNOWN`。既有双 tenant、角色/owner/partner/warehouse 字段边界、非法
Origin、无效 token、猜测 UUID 和安全错误仍必须通过。MCP Provider 不得为了测试通过而放宽
源应用服务权限。
