# 分页、筛选与并发契约

## 1. Cursor 分页

列表响应：

```json
{
  "items": [],
  "pageInfo": {
    "nextCursor": null,
    "hasNext": false,
    "pageSize": 25
  }
}
```

cursor 为签名/编码不透明值，至少绑定：tenant、最后排序键、filter hash、sort、过期/版本。客户端不得解析。

## 2. 稳定排序

每个列表有默认稳定排序：

- partners: `-updatedAt,number`；
- quotations: `-createdAt,number`；
- work items: `dueAt,-priority,id`；
- events/audit: `-occurredAt,-id`。

用户排序字段白名单，最后总加唯一 ID 打破同值。

## 3. 筛选

- 枚举可重复或逗号列表按契约固定；
- 日期使用 `from/to`，明确包含/排除边界；
- keyword 长度、字符和最大 token 限制；
- 所有筛选参数参数化查询；
- tenant 和 permission predicate 必须先应用；
- 未知筛选字段返回 400，不静默忽略。

## 4. 并发

读响应：

```text
ETag: "7"
Last-Modified: ... (可选)
```

写请求：

```text
If-Match: "7"
```

失败：412 `RESOURCE_VERSION_CONFLICT`，可返回：

```json
{
  "code": "RESOURCE_VERSION_CONFLICT",
  "currentVersion": 8,
  "currentState": "PENDING_APPROVAL",
  "allowedActions": ["VIEW"]
}
```

不返回未经授权的最新字段。

## 5. Snapshot 查询

对于报价发送/客户接受等历史语义，查询指定 revision 或默认当前对外修订；不把最新主数据混入快照。API 字段用 `sourceVersion`, `capturedAt` 明确。

## 6. 导出

P1 可不实现通用导出。若实现：异步任务、行数限制、权限/租户过滤、CSV 注入防护、短期签名下载、审计。不可绕过列表字段安全。
