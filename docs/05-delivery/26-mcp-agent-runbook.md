# MCP 与智能客户端运行手册

## 1. 当前边界

CellarBridge 在同一 Spring Boot 后端的 `/mcp` 暴露经过认证、只读、权限感知的 MCP
能力。它使用无状态 Streamable HTTP、专用 canonical MCP resource/audience、`mcp:read`
scope 与预登记 `cellarbridge-mcp-host`，并继续复用 TenantContext 与业务权限。

当前只有六个 Tool、一个固定 Resource、两个 Resource Template 和三个 Prompt。Envelope 2.0
提供严格 Tool schema、四个集合 Tool 的签名 cursor、可解释 freshness 与响应预算。

当前仍没有写操作、自动审批、内置模型、RAG、
持久对话记忆、动态客户端注册、token exchange 或 credential forwarding。MCP 客户端必须
自行提供兼容协议的 host 与模型（如需），服务端不会代替用户执行交易动作。

## 2. 前置条件与启动

需要 Java 21、Docker 及可用的本地 PostgreSQL/Keycloak 演示环境。MCP token 必须来自
`cellarbridge-mcp-host` 的真实 Authorization Code + S256 PKCE 流程，并绑定配置的 HTTPS
resource；普通 `cellarbridge-api` token 会被 `/mcp` 拒绝。

```bash
CELLARBRIDGE_MCP_ENABLED=true make dev-core
curl --fail --silent --show-error \
  http://localhost:8080/actuator/health/readiness
```

`CELLARBRIDGE_MCP_ENABLED` 生产安全默认 `false`；demo/test 与 Compose 显式启用。需要临时
关闭 MCP 时，以 `false` 重启后端；关闭不影响 REST、事件消费或报表投影。

启用时至少显式配置 `CELLARBRIDGE_MCP_RESOURCE`/`AUDIENCE`（必须相同且为 canonical HTTPS
URI）、`SCOPE`、`ALLOWED_CLIENTS`、`ALLOWED_HOSTS` 与 `ALLOWED_ORIGINS`。还可收紧
`MAX_REQUEST_BYTES`、`RATE_CAPACITY`/`RATE_REFILL_PER_SECOND`、`MAX_CONCURRENCY`/
`MAX_CLIENT_CONCURRENCY`、`REST_CONNECTION_RESERVE`、`DB_POOL_SIZE`、`REQUEST_TIMEOUT`
和 `STATEMENT_TIMEOUT`。启动取值必须满足
`MAX_CONCURRENCY + REST_CONNECTION_RESERVE <= DB_POOL_SIZE`；否则 `/mcp` 以 503 fail
closed、MCP health 为 `MISCONFIGURED`，主应用不退出。该不变量只为共享 Hikari pool 留出
非 MCP headroom，不是独立连接池或 REST 专属槽位。

### 生产域名与存量 realm

一次生产变更必须原子同步：`CELLARBRIDGE_MCP_RESOURCE` 与 `AUDIENCE` 使用同一个外部
canonical HTTPS `/mcp` URI；`cellarbridge-mcp.attributes.resource_url` 与之相同；TLS 终止点
证书可信，`ALLOWED_HOSTS` 是真实外部 authority；host client 的 redirect URI/web origins
使用精确 HTTPS 值。任一核验失败都保持 MCP 关闭，禁止从 Host/Forwarded 推导上述 URI。

`--import-realm` 只初始化不存在的 realm，不升级存量 realm。保持 MCP 关闭，先部署含 provider
且启用两个 SPI flag 的 Keycloak image。再用 secret manager 提供的短期、最小权限管理员身份
登录（下列命令会交互式读取密码），按 scope、完整 profile/policy 集合、client 的顺序幂等升级：

```bash
set -euo pipefail
KC="${KEYCLOAK_HOME:?}/bin/kcadm.sh"; KC_REALM="${KC_REALM:-cellarbridge}"
: "${KC_URL:?} ${KC_ADMIN:?} ${CB_MCP_RESOURCE:?} ${CB_MCP_REDIRECT_URI:?} ${CB_MCP_HOST_ORIGIN:?}"
KCADM_CONFIG="$(mktemp)"; trap 'rm -f "$KCADM_CONFIG"' EXIT
"$KC" config credentials --config "$KCADM_CONFIG" --server "$KC_URL" --realm master --user "$KC_ADMIN"
REALM_JSON=deploy/keycloak/cellarbridge-realm.json
scope_doc() { jq '.clientScopes[]|select(.name=="mcp:read")' "$REALM_JSON"; }
scope_id="$("$KC" get client-scopes -r "$KC_REALM" --config "$KCADM_CONFIG" |
  jq -r 'map(select(.name=="mcp:read"))[0].id//empty')"
if [[ -n "$scope_id" ]]; then scope_doc|"$KC" update "client-scopes/$scope_id" -r "$KC_REALM" --config "$KCADM_CONFIG" -f -;
else scope_doc|"$KC" create client-scopes -r "$KC_REALM" --config "$KCADM_CONFIG" -f -; fi
"$KC" get client-policies/profiles -r "$KC_REALM" --config "$KCADM_CONFIG" |
  jq --slurpfile d "$REALM_JSON" '.profiles=([.profiles[]?|select(.name!="cellarbridge-mcp-resource-profile")]+[$d[0].clientProfiles.profiles[0]])' |
  "$KC" update client-policies/profiles -r "$KC_REALM" --config "$KCADM_CONFIG" -f -
"$KC" get client-policies/policies -r "$KC_REALM" --config "$KCADM_CONFIG" |
  jq --slurpfile d "$REALM_JSON" '.policies=([.policies[]?|select(.name!="cellarbridge-mcp-resource-policy")]+[$d[0].clientPolicies.policies[0]])' |
  "$KC" update client-policies/policies -r "$KC_REALM" --config "$KCADM_CONFIG" -f -
desired() { jq --arg id "$1" --arg r "$CB_MCP_RESOURCE" --arg u "$CB_MCP_REDIRECT_URI" --arg o "$CB_MCP_HOST_ORIGIN" \
  '.clients[]|select(.clientId==$id)|if $id=="cellarbridge-mcp" then .attributes.resource_url=$r else .redirectUris=[$u]|.webOrigins=[$o]|del(.rootUrl,.baseUrl) end' "$REALM_JSON"; }
upsert() { local id; id="$("$KC" get clients -r "$KC_REALM" -q "clientId=$1" --fields id --config "$KCADM_CONFIG"|jq -r '.[0].id//empty')";
  if [[ -n "$id" ]]; then desired "$1"|"$KC" update "clients/$id" -r "$KC_REALM" --config "$KCADM_CONFIG" -f -;
  else desired "$1"|"$KC" create clients -r "$KC_REALM" --config "$KCADM_CONFIG" -f -; fi; }
upsert cellarbridge-mcp; upsert cellarbridge-mcp-host
"$KC" get client-scopes -r "$KC_REALM" --config "$KCADM_CONFIG" | jq -e 'any(.name=="mcp:read")'
"$KC" get client-policies/profiles -r "$KC_REALM" --config "$KCADM_CONFIG" |
  jq -e 'any(.profiles[]; .name=="cellarbridge-mcp-resource-profile" and any(.executors[]; .executor=="cellarbridge-mcp-resource-binding"))'
"$KC" get client-policies/policies -r "$KC_REALM" --config "$KCADM_CONFIG" |
  jq -e 'any(.policies[]; .name=="cellarbridge-mcp-resource-policy" and .enabled==true and .mode=="STRICT" and any(.profiles[]; .=="cellarbridge-mcp-resource-profile"))'
"$KC" get clients -r "$KC_REALM" -q clientId=cellarbridge-mcp --config "$KCADM_CONFIG" |
  jq -e --arg r "$CB_MCP_RESOURCE" '.[0].attributes.resource_url==$r'
"$KC" get clients -r "$KC_REALM" -q clientId=cellarbridge-mcp-host --config "$KCADM_CONFIG" |
  jq -e --arg u "$CB_MCP_REDIRECT_URI" --arg o "$CB_MCP_HOST_ORIGIN" '.[0]|.redirectUris==[$u] and .webOrigins==[$o] and .attributes["cellarbridge.mcp.resource-enforced"]=="true"'
```

随后以真实 Host/TLS 读取 protected-resource metadata，并执行 Code + S256 的正确 resource 正向
与错误 resource 负向核验，再启用后端。管理员凭据、client secret、token、authorization code
和 PKCE verifier 只进入 secret/session 工具，不入库、不落 artifact、不出现在命令输出。

`CELLARBRIDGE_MCP_MAX_PAGE_SIZE=100` 与 `CELLARBRIDGE_MCP_MAX_COLLECTION_ITEMS=1000`
可向下收紧；`CELLARBRIDGE_MCP_MAX_RESPONSE_BYTES=262144` 可在 1 KiB 至 4 MiB 配置；
超限返回 `RESULT_TOO_LARGE`。Supply/Reporting cursor TTL 分别由
`CELLARBRIDGE_CATALOG_CURSOR_TTL`、`CELLARBRIDGE_AUDIT_CURSOR_TTL` 控制，默认 `PT15M`。

为手工 smoke 准备变量。token 应通过本地 OIDC 登录流程取得；不要把真实 token 写入文档、
脚本、命令参数文件、截图或日志，也不要 `echo` 输出。

```bash
export CB_MCP_URL="http://localhost:8080/mcp"
export CB_MCP_ORIGIN="http://localhost:5173"
export CB_MCP_PROTOCOL_VERSION="2025-11-25"
export CB_ACCESS_TOKEN="<oidc-access-token>"
export CB_SKU_ID="<authorized-sku-uuid>"
export CB_SUBJECT_TYPE="ORDER"
export CB_SUBJECT_ID="<authorized-order-uuid>"
```

生产部署必须把 `CB_MCP_ORIGIN` 替换为实际白名单 Origin，并通过正式 secret/session 工具向
客户端提供 token。Host 可先读取 `/.well-known/oauth-protected-resource/mcp`，或从 401
challenge 的 `resource_metadata` 发现 authorization server、resource 与最小 scope；客户端
仍须预登记，不能动态注册。

## 3. HTTP headers

```text
Authorization: Bearer <access-token>; Origin: <allowed-origin>; Content-Type: application/json; Accept: application/json, text/event-stream; MCP-Protocol-Version: <negotiated-version>
```

initialize 用请求体协商 protocol version；后续请求发送协商后的
`MCP-Protocol-Version`。浏览器与本手册的安全 smoke 必须发送 Origin；native host 若发送
Origin，也必须匹配服务端白名单。无状态模式不要求客户端保存 `Mcp-Session-Id`，也不能依赖
前一请求的 TenantContext。

下面命令通过函数集中设置 headers。它们只定义可执行步骤，不代表当前环境已经返回成功结果。

```bash
mcp_post() {
  curl --fail-with-body --silent --show-error --no-buffer \
    -X POST "$CB_MCP_URL" \
    -H "Authorization: Bearer $CB_ACCESS_TOKEN" \
    -H "Origin: $CB_MCP_ORIGIN" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "MCP-Protocol-Version: $CB_MCP_PROTOCOL_VERSION" \
    --data "$1"
}
```

## 4. 协议 smoke

### Initialize

当前基线锁定 MCP protocol `2025-11-25`：

```bash
mcp_post '{"jsonrpc":"2.0","id":"initialize-1","method":"initialize","params":{"protocolVersion":"'"$CB_MCP_PROTOCOL_VERSION"'","capabilities":{},"clientInfo":{"name":"cellarbridge-smoke","version":"1.0"}}}'
mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
```

检查 initialize response 的 server info、capabilities 与 negotiated protocol version。不要把
notification 的空响应误判为业务结果。

### Tool discovery 与调用

```bash
mcp_post '{"jsonrpc":"2.0","id":"tools-list-1","method":"tools/list","params":{}}'
mcp_post '{"jsonrpc":"2.0","id":"tools-call-1","method":"tools/call","params":{"name":"cellarbridge_current_user","arguments":{}}}'
```

验证 `tools/list` 恰好包含六个已登记 Tool，且每个 Tool 都声明：

```text
readOnlyHint=true; destructiveHint=false; openWorldHint=false
```

`tools/call` 成功结果的 `structuredContent` 与 text content JSON 应包含
`schemaVersion`、`sourceKind`、`dataAsOf`、`projectionStatus`、`correlationId`、
`freshness`、对象式 `warnings` 和 `data`，业务版本固定为 `2.0`。逐个验证六个 Tool 的
严格 `outputSchema`、`structuredContent` 与 text JSON 等价；完整字段规则见
[MCP 能力合同](../04-contracts/10-mcp-capability-contract.md)。业务失败还要检查协议级
`isError=true` 与安全 error envelope，不能只检查 HTTP 200。

### 四个集合 Tool 的翻页

`cellarbridge_search_supply`、`cellarbridge_list_work_items`、
`cellarbridge_get_timeline` 和 `cellarbridge_search_audit` 都返回
`pageInfo:{nextCursor,hasNext,pageSize}`。`hasNext=true` 时把 cursor 原样加入同名 Tool 的
下一次 arguments，并保持其他参数与绑定的身份/权限不变。Supply 会在每页重新校验当前 warehouse
assignment。cursor 默认 15 分钟失效；修改、过期或跨 Tool/filter/tenant/绑定权限复用均返回
`CURSOR_INVALID`，也不得进入日志或 Resource URI。

### Resource discovery 与读取

```bash
mcp_post '{"jsonrpc":"2.0","id":"resources-list-1","method":"resources/list","params":{}}'
mcp_post '{"jsonrpc":"2.0","id":"resource-templates-list-1","method":"resources/templates/list","params":{}}'
mcp_post '{"jsonrpc":"2.0","id":"resource-read-me-1","method":"resources/read","params":{"uri":"cellarbridge://session/me"}}'
```

带 UUID 的资源使用授权范围内的合成对象：

```bash
mcp_post '{"jsonrpc":"2.0","id":"resource-read-sku-1","method":"resources/read","params":{"uri":"cellarbridge://catalog/skus/'"$CB_SKU_ID"'"}}'
mcp_post '{"jsonrpc":"2.0","id":"resource-read-timeline-1","method":"resources/read","params":{"uri":"cellarbridge://timeline/'"$CB_SUBJECT_TYPE"'/'"$CB_SUBJECT_ID"'"}}'
```

URI 只接受合同声明的模板、subject type 与 canonical UUID。不要把 token、tenant、cursor 或
付款参考拼入 URI。Timeline Resource 返回默认首屏；若
`data.pageInfo.hasNext=true`，使用 `cellarbridge_get_timeline` 的相同
`subjectType/subjectId` 与返回的 `nextCursor` 继续，不要尝试给 Resource URI 添加 query。

### Prompt discovery 与读取

```bash
mcp_post '{"jsonrpc":"2.0","id":"prompts-list-1","method":"prompts/list","params":{}}'
mcp_post '{"jsonrpc":"2.0","id":"prompts-get-1","method":"prompts/get","params":{"name":"cellarbridge_daily_operations_brief","arguments":{}}}'
```

Prompt 只能包含公开步骤、输入说明和建议 Tool；它不应自动调用 Tool，也不应包含业务记录、
token、隐藏权限或客户数据。

## 5. 安全与权限 smoke

下列检查必须分别记录 HTTP 状态、JSON-RPC/error code 和经过脱敏的响应结构，不保存 token。

### 未认证与非法 Origin

```bash
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  -X POST "$CB_MCP_URL" \
  -H "Origin: $CB_MCP_ORIGIN" \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","id":"unauthenticated-1","method":"tools/list","params":{}}'

curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' \
  -X POST "$CB_MCP_URL" \
  -H "Authorization: Bearer $CB_ACCESS_TOKEN" \
  -H "Origin: https://invalid.example" \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","id":"origin-1","method":"tools/list","params":{}}'
```

预期分类分别为 401 和 403。还需用过期 token、错误 audience 和错误 issuer 验证 401，不能把
认证失败包装成成功 Tool result。

### 角色、字段与 tenant

至少使用合成身份执行：

- Sales：供给只能看到数量带，不能看到 exact lot；
- Warehouse：exact lot 只覆盖 warehouse assignment；
- Buyer：供给和审计 Tool 拒绝；
- 普通 Sales：`scope=team` 工作项拒绝；Quotation/Trade Order/Order timeline 采用更严格
  fail-closed；
- 两个 tenant：相同搜索词、猜测 UUID 和 cursor 不串租；
- Auditor/System Operator/Finance：只得到其字段分类允许的审计或 Dashboard 投影。

Reporting 使用 `SOURCE_WATERMARK`：同步且无 pending/dead letter 为 `CURRENT`，source
领先或证据不足为 `STALE`，staging 为 `REBUILDING`，矛盾为 `UNKNOWN`，三方无证据才为
`EMPTY`。Supply 有结果时使用 `OBSERVATION_AGE/UNKNOWN` 与
`FRESHNESS_NOT_SOURCE_VERIFIED`，不能由数据年龄推断 projector 健康；完整状态矩阵和 warning
规则见 [MCP 能力合同](../04-contracts/10-mcp-capability-contract.md)。

响应和日志中搜索下列禁止内容时，只记录匹配计数和脱敏位置，不复制敏感值：

```text
Authorization; Bearer; eyJ; SELECT; INSERT; com.rom.cellarbridge; java.lang; at com.
```

## 6. 自动化验证

仓库级最低门禁：

```bash
./mvnw -pl backend,keycloak-resource-binding -am verify
make mcp-smoke
make mcp-conformance
make mcp-production-security
make verify-container-security
python3 scripts/validate_repository.py --scope all
git diff --check
```

`make mcp-smoke` 使用隔离的 Compose project 启动 PostgreSQL、Keycloak 和后端，通过真实
Authorization Code + S256 PKCE 和两阶段 RFC 8707 resource 分别取得 Sales 与 Buyer token，
再执行 initialize、discovery、call/read 与负向安全矩阵。`make mcp-production-security`
进一步覆盖 metadata/challenge、authorization/token/refresh resource 负向矩阵、API/MCP token
隔离、Host/Origin/body/rate/bulkhead/SQL timeout、共享连接池配置下的 REST headroom、health、metrics 与日志扫描。
它不会使用 Resource Owner Password Credentials，也不会打印 token。
只运行 MockMvc 或单元测试不能替代该门禁。后端 verify 还必须覆盖六个 Tool 的 schema/结果
一致性、四个集合 Tool 的首/中/末页及 cursor 篡改/跨查询/跨 tenant/过期、256 KiB/1,000
元素预算，以及 source-watermark freshness 状态矩阵。

## 7. 官方 MCP conformance

仓库锁定官方 `@modelcontextprotocol/conformance@0.1.16` 与 MCP specification
`2025-11-25`。执行：

```bash
make mcp-conformance
```

脚本先完成上述真实 OIDC smoke，再以 loopback-only proxy 向官方 runner 注入短期 Token；
proxy 不记录或持久化 Token，也不接受远程连接。官方 runner 实际执行 5 个与本服务声明能力
相符的 server scenarios：

- `server-initialize`、`ping`、`tools-list`、`resources-list`、`prompts-list`

默认原始输出位于 `target/mcp-conformance`。为保留公开证据可显式指定：

```bash
CB_MCP_CONFORMANCE_OUTPUT=docs/evidence/mcp/conformance-v0.1.16 \
  make mcp-conformance
```

动态客户端注册、token exchange、credential forwarding、写入、模型 sampling、RAG 和未声明
的可选 capability 不属于通过声明；未执行场景不得改写为 pass。

## 8. 故障排查

| 现象 | 检查 | 处理 |
|---|---|---|
| `/mcp` 不可达或 404 | readiness、`CELLARBRIDGE_MCP_ENABLED`、后端端口 | 启动后端或以 `true` 重启；不要开放备用未认证路由 |
| metadata 为 503 | MCP resource/audience/client/scope/Host 配置不完整或不一致 | 修复显式配置；不得从请求 Host/Forwarded 动态拼接 canonical URI |
| 401 | issuer/JWK、专用 resource/audience/client、exp/nbf、签名与 Bearer 格式 | 重新执行带相同 resource 的 Code + S256 流程；API token 不能复用 |
| 403 | `mcp:read` scope、Origin 白名单、业务权限、team scope、纯 Sales timeline 边界 | 使用允许 Origin/授权身份；不要降低 scope 或把 tenant/角色放入 arguments |
| 413/429 | body 上限或 client token-bucket；429 应含 `Retry-After` | 缩小请求/降低速率；不要只提高上限 |
| 503 / `DEPENDENCY_OVERLOADED` | 全局/每-client bulkhead 或 executor 饱和 | 查 MCP health/低基数指标及共享连接池余量；只可据门禁确认有界 demand 与该配置实测 headroom，不宣称独立 REST reserve |
| `DOWNSTREAM_TIMEOUT` | request/statement timeout、数据库锁等待或下游迟滞 | 用安全 correlation 定位；确认连接池恢复，禁止回显 SQL/异常 |
| Keycloak 暂时不可用 | 新登录/未知 kid 失败；已缓存 JWK 的未过期 token 可能继续通过 | 不摘除全部 REST 实例；恢复/轮换后重跑 resource 与双 audience smoke |
| JSON-RPC invalid request | `jsonrpc`, `id`, `method`, `params`、Content-Type、protocol version | 按 initialize 协商结果修正请求 |
| Tool 不在列表 | 服务端 capability、启停变量、版本 | 核对只应存在的六个 Tool；不要动态注册任意 Tool |
| `CURSOR_INVALID` | cursor 是否来自同一 Tool/filter/tenant/授权身份，是否超过默认 15 分钟 TTL | 从第一页重新查询；不要解析、修改、记录或跨上下文复用 cursor |
| `RESULT_TOO_LARGE` | `pageSize`、供给 exact lot 数、256 KiB/1,000 元素预算 | 缩小 page 或增加更窄筛选；不要提高预算来掩盖无界查询 |
| SKU/subject 不存在 | canonical UUID、当前 tenant 与权限范围 | 使用当前身份可见的合成对象；安全 404 不证明其他 tenant 是否存在 |
| 看不到 exact lot | `inventory:read-exact` 与 warehouse assignment | 这是字段级安全，不应通过其他 Tool 绕过 |
| Reporting 为 `STALE`/`REBUILDING`/`UNKNOWN`/`EMPTY` | `freshness` 水位、pending/dead letter、generation、稳定 warning code | 按报表运行手册诊断；不要把投影视为交易真相 |
| Supply 为 `UNKNOWN` | `freshness.mode=OBSERVATION_AGE` 与 `FRESHNESS_NOT_SOURCE_VERIFIED` | 这是当前合同的诚实边界；预占时重新校验，不要擅自改成 `CURRENT` |
| 安全 `INTERNAL_ERROR` | correlationId 与脱敏结构化日志 | 用 correlation 定位服务端；不要向客户端返回原始异常 |
| 请求间身份看似复用 | TenantContext filter 清理测试、并发双身份请求 | 停止暴露端点并修复；无状态模式禁止共享身份 |

诊断只记录 correlationId、稳定 error code、服务版本和必要的低基数字段。不得在 issue、PR、
截图或 conformance artifact 中保存 access token、请求完整 body、客户数据或内部异常。

## 9. 回滚

```bash
CELLARBRIDGE_MCP_ENABLED=false make dev-core
```

生产环境先关闭或回滚后端并确认 `/mcp` 与 metadata 已撤回；Keycloak provider/SPI 必须按下述
realm 恢复并验证后再撤，不能在 provider disabled 时开放 MCP，也不能退回 API audience token。
变更前在加密文件系统保存完整集合与对象存在性；对象快照为 `[]` 表示变更前不存在：

```bash
umask 077; backup="${CB_MCP_BACKUP_DIR:?new encrypted backup directory required}"; mkdir "$backup"
"$KC" get client-policies/profiles -r "$KC_REALM" --config "$KCADM_CONFIG" >"$backup/profiles.json"
"$KC" get client-policies/policies -r "$KC_REALM" --config "$KCADM_CONFIG" >"$backup/policies.json"
"$KC" get client-scopes -r "$KC_REALM" --config "$KCADM_CONFIG" | jq '[.[]|select(.name=="mcp:read")]' >"$backup/mcp-read.json"
for id in cellarbridge-mcp cellarbridge-mcp-host; do "$KC" get clients -r "$KC_REALM" -q "clientId=$id" --config "$KCADM_CONFIG" >"$backup/$id.json"; done
```

回滚保持 MCP 关闭且暂留 provider/SPI：非空对象快照必须 `jq '.[0]'` 后恢复，先 scope 后 clients；
快照为 `[]` 时按 name/clientId 查询当前 UUID，先删 clients 后 scope。完整集合按 policies → profiles
恢复，避免仍被 policy 引用的 profile 先删除。同步恢复 resource/audience、Host/TLS、redirect/origins。
验证全部快照后才能撤 provider；client upsert 是权威全对象替换，须审阅差异；realm import 不适用。
密钥轮换先发布新 JWK、等待缓存过渡，再撤旧 key，并重跑两种 audience 的正反向验证。MCP
不写业务数据、没有 migration 或持久 session，所以不需要数据补偿；不要增加无认证 fallback。
