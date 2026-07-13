# API 设计规范

## 1. 范围

本文定义浏览器和受控外部客户端使用的 REST API。模块内部 Java API 和事件另见领域/AsyncAPI 文档。

基础路径：`/api/v1`。客户门户使用 `/api/v1/portal`，但仍受身份/受控 token 和租户上下文约束。

## 2. 资源与命令

读取使用资源语义：

```text
GET /partners
GET /partners/{partnerId}
GET /quotations/{quotationId}
```

具有明确业务动作的状态转换使用命令子资源，避免通用 PATCH status：

```text
POST /partners/{id}/submission
POST /partners/{id}/approval
POST /quotations/{id}/route-evaluations
POST /quotations/{id}/submission
POST /quotations/{id}/issue
POST /portal/quotations/{token}/acceptance
POST /orders/{id}/cancellation
```

命令端点返回 200/201/202 取决于同步结果；长流程返回当前状态和可查询 Location，不伪装完成。

## 3. 标识和编号

- URL 使用 UUID；
- 响应同时返回不可变业务编号；
- 客户公开 token 不等于资源 ID；
- 不暴露数据库序列主键；
- tenantId 不由普通客户端在 body 指定。

## 4. 媒体类型和编码

- JSON UTF-8；
- 成功 `application/json`；
- 错误 `application/problem+json`；
- 时间 RFC 3339 UTC；
- 日期 `YYYY-MM-DD`；
- 金额对象 `{ "amount": "123.45", "currency": "CNY" }`，amount 为字符串避免 JS 浮点；
- 数量为 decimal string 或 integer，按 unit contract；
- UUID 小写 canonical。

## 5. 版本与并发

- 可变资源响应包含 `ETag: "<version>"`；
- 写命令使用 `If-Match` 或 request `expectedVersion`，契约逐端点明确；
- 缺失必要 If-Match 返回 428；
- 不匹配返回 412；
- API v1 向后兼容扩展优先；破坏变化需要新版本/迁移决策。

## 6. 幂等

指定命令要求 `Idempotency-Key`：客户接受、报价转订单（内部）、库存预占、登记付款、恢复动作、外部 adapter 命令。

规则见 `02-idempotency.md`。重复相同 key+request hash 返回原状态/结果；相同 key 不同 payload 返回 409 `IDEMPOTENCY_KEY_REUSED`。

## 7. 列表、筛选和排序

默认：

```text
?pageSize=25&cursor=...&sort=-updatedAt,number&status=ACTIVE
```

- pageSize 默认 25，最大 100；
- 服务端白名单排序；
- cursor 不透明并绑定 tenant/filter/sort；
- 响应含 `items`, `pageInfo.nextCursor`, `pageInfo.hasNext`；
- 小型稳定后台表可使用 page/size，但同一 endpoint 不混用；
- tenant/permission 过滤在分页前执行。

## 8. 字段投影

可使用固定 `include` 扩展，例如 `?include=timelineSummary`，不提供任意字段/expand。敏感字段使用专用授权 response schema，客户门户绝不复用内部 DTO。

## 9. 错误

Problem Details 示例：

```json
{
  "type": "https://cellarbridge.dev/problems/quote-expired",
  "title": "Quotation has expired",
  "status": 409,
  "detail": "Quotation QUO-202607-000123 can no longer be accepted.",
  "instance": "/api/v1/portal/quotations/.../acceptance",
  "code": "QUOTE_EXPIRED",
  "traceId": "...",
  "retryable": false,
  "errors": []
}
```

生产 detail 不暴露堆栈、SQL、内部类或其他租户存在性。

## 10. HTTP 语义

| 状态 | 用途 |
|---|---|
| 200 | 查询、幂等重放、同步命令完成 |
| 201 | 新资源创建，含 Location |
| 202 | 长流程已接受，含状态资源 |
| 204 | 无 body 成功（谨慎） |
| 400 | 格式/基础校验 |
| 401 | 未认证/无效 token |
| 403 | 已认证但无权限 |
| 404 | 租户/权限范围内不存在 |
| 409 | 业务状态/唯一/幂等冲突 |
| 412 | 版本不匹配 |
| 422 | 语法正确但复杂业务输入不可处理（仅约定端点） |
| 428 | 缺失 If-Match |
| 429 | 限流 |
| 503 | 临时不可用，可按响应重试 |

## 11. 安全头和缓存

- 交易详情默认 `Cache-Control: no-store`；
- 公开静态资源可长缓存；
- 客户 portal token 页面 `Referrer-Policy: no-referrer`；
- CSP/HSTS 等由部署配置；
- CORS 白名单；
- 认证响应不在共享缓存。

## 12. OpenAPI 要求

每个 operation：

- 唯一 operationId；
- tags 和 summary；
- security；
- request/response schema；
- 常见错误；
- idempotency/concurrency header；
- 示例使用合成数据；
- 权限码 extension 可使用 `x-permissions`；
- requirement ID 可使用 `x-requirements`。

OpenAPI 文件是实现基线，实际应用导出的契约必须与其一致。
