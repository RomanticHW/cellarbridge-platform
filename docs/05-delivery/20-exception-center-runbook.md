# Exception Center 与恢复运行手册

## 1. 当前边界

Task 10 将库存不足、履约步骤失败/逾期和最终事件投递失败转换为租户内可分派、可审计的 Exception Case。当前实现用于合成业务演示，不发送真实邮件/短信，不允许编辑商业事件 payload，也不以“关闭异常”替代源业务成功。

Task 10 处于 **Available**，完整纵向切片已通过合并门禁。

## 2. 可观察行为

- `tenant_id + active dedup_key` 只允许一个开放 Case；不同源事件追加不可变 occurrence，精确重放不重复 occurrence、工作项、通知或 `ExceptionOpenedV1`。
- Case 严格按 `OPEN → ASSIGNED → ACKNOWLEDGED → IN_PROGRESS → RECOVERY_PENDING → RESOLVED → CLOSED` 推进，并使用 `If-Match` 防止陈旧页面覆盖新证据。
- 恢复目录包含库存 Reservation 重试、失败 Fulfillment Step 重试、逾期 Step 恢复、失败 publication 重放和人工证据确认。请求保存输入摘要和原因，不保存 secret 或任意 payload。
- 库存重试在 Inventory 事务中追加 `MANUAL_RETRY` Attempt；业务失败保留源 Attempt 和异常 Recovery Outcome，成功后发布一次 `InventoryReservationConfirmedV1`。
- 履约失败重试回读 `READY/BLOCKED + IN_PROGRESS`；逾期恢复清除 overdue 标记并按原步骤窗口重建 due time。未返回预期源状态时 Case 保持可处理。
- 同一恢复类型最多尝试三次；第三次失败提升严重度并幂等记录升级通知，第四次在新增 Attempt 前被拒绝。
- 同一幂等键在一分钟执行租约内只允许一个执行者；超时接管仍使用同一源 causation/command，库存 Attempt、履约 command 和 Inbox 重放均返回原副作用结果。
- 普通关闭要求 Case 已由源状态验证进入 `RESOLVED`。`FALSE_POSITIVE` 和 `DUPLICATE` 只允许从 `OPEN` 快速关闭，均要求原因；Duplicate 另要求同租户主 Case ID，并在关闭 Case 上保留关联。

## 3. 失败 publication 重放

- `GET /api/v1/event-publications/failed` 只返回 event type/id、consumer、attempt、错误码、next retry 和版本；tenant、producer、subject number 与 payload 不进入 DTO。
- `REPLAY_PUBLICATION` 需要 `event-publication:replay`、原因、Case expected version 和恢复幂等键。
- 重放只把现有 Inbox delivery 调整为有界 `FAILED_RETRYABLE`；不删除 Inbox、不改 payload、不创建第二条同 consumer/event Inbox 记录。
- 最终失败扫描和重复扫描使用稳定键建单，不重复技术通知。

## 4. 权限与租户

- `exception:read`：读取授权租户内 Case；Customer 身份被拒绝。
- `exception:assign`：分派负责人。
- `exception:recover`：确认、调查、恢复和审核关闭。
- `event-publication:read/replay`：读取掩码技术队列和执行受控重放。
- System Operator 只能读取和操作 `EVENT_DELIVERY_FAILED` Case，商业库存/履约 Case 按不存在处理；跨租户资源同样按不存在处理。

## 5. API 与页面

- `GET /api/v1/exceptions`：状态、严重度、负责人、源类型和逾期过滤，不透明游标分页。
- `GET /api/v1/exceptions/{exceptionId}`：安全源摘要、occurrence、历史、Recovery Attempt/Outcome 和当前允许动作。
- assignment、actions、recovery-attempts、closure 命令要求原因；版本化命令要求 `If-Match`，恢复另要求 `Idempotency-Key`。
- `/app/exceptions`：队列、严重度/状态/负责人/源/逾期过滤和权限控制的失败 publication tab。
- `/app/exceptions/{exceptionId}`：证据时间线、状态历史、恢复记录和审核对话框。

## 6. 数据与兼容性

- V19 只新增 `exception_center` schema 和表，不改写 V2–V18；迁移归属与 SHA-256 记录在 `migration-ownership.csv`。
- Case、Occurrence、History、Recovery、Work Item 与 Notification 的数据库外键同时包含 `tenant_id`，拒绝跨租户事实关联。
- OpenAPI 从 1.9 增量扩展到 1.10；既有路径不删除。AsyncAPI 已有的 `ExceptionOpenedV1` schema 与当前生产者一致。
- `safe_details`、occurrence evidence 和恢复 input summary 均为安全摘要；原商业事件 payload 仍由 publication 存储拥有且不经 Exception API 返回。

## 7. 验证入口

```bash
./mvnw -pl backend -am verify
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend lint
pnpm --dir frontend build
make exception-e2e
python3 scripts/validate_repository.py --scope public
```

核心自动证据包括 `ExceptionCenterIntegrationTest`、`FulfillmentOrchestrationIntegrationTest`、`TradeOrderCreatedReservationIntegrationTest`、`ExceptionWorkspace.test.tsx` 和真实 OIDC/PostgreSQL Playwright 流程。
