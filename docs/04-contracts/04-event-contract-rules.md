# 事件契约规范

## 1. 命名

AsyncAPI channel/topic 名：`cellarbridge.<domain>.<fact>.v1`。

事件 `type` 同名；Java class 可为 `QuotationAcceptedV1`。

## 2. Envelope

所有外部事件使用统一 envelope schema：

- `id`；
- `type`；
- `specVersion`；
- `occurredAt`；
- `tenantId`；
- `producer`；
- `subject`；
- `correlationId`；
- `causationId`；
- `payload`；
- `metadata`（受控可选）。

## 3. Payload 原则

- 事件自足到消费者不需回查上游私有表；
- 只含稳定必要字段；
- 金额 amount string + currency；
- 快照含 source ID/version；
- enum 扩展有安全 unknown 策略；
- 不序列化 ORM object graph；
- 个人/商业敏感最小化；
- 不把技术异常堆栈放 payload。

`QuotationAcceptedV1` 的 Snapshot Hash V1 字段序固定为 `schemaVersion=1, quotationId, revisionId, quotationNumber, revision, customer, currency, totalAmount, paymentTermDays, route, acceptedTermsVersion, requestedDeliveryDate, deliveryAddress, lines`；排除 acceptance/envelope/hash 字段，保留行序，显式写 null，金额/数量用去尾零十进制字符串，日期用 ISO 格式，再对 UTF-8 紧凑 JSON 做 SHA-256。当前 Schema 与新生产者唯一格式是裸小写 64 位十六进制；`sha256:` 只兼容读取 pre-1.0 已持久化消息/历史行，规范化后比较且新写入仍为裸值，不代表 Schema 重新允许前缀，v1.0 后删除兼容路径须另行决策。消费者在持久化前校验长度、精度、行/SKU 唯一和求和边界；确定性约束失败为 `ORDER_PERSISTENCE_CONSTRAINT_VIOLATION`，已知瞬态存储失败为 `ORDER_STORAGE_UNAVAILABLE`，未知数据访问异常继续遵守 ADR-013 有界兜底。

嵌套字段序固定为 customer 的 `partnerId, partnerNumber, displayName, sourceVersion`，route 的 `code, policyVersion, estimatedDeliveryDate`，address 的 `countryCode, province, city, district, line1, postalCode`，line 的 `quotationLineId, skuId, skuCode, description, quantity, unit, netUnitPrice, lineTotal, supplyPoolId, supplyType`；`acceptanceId`、`acceptedAt`、`sourceOwnerId`、`snapshotHash` 和 envelope 不进入 projection。

## 4. 版本

同版本兼容变化：新增可选字段；放宽消费者可忽略内容；补充非语义描述。

pre-1.0 仓库内同步升级允许 V1 Current 增加完整 `supplyDecision`/`allocationMode`；Legacy 必须同时缺失，显式 null 或混合形态失败关闭。消费者从 Root+Lines 重建并重算 Decision Hash。

pre-1.0 首次启用库存预留结果事件时，V1 可新增可选执行证据和终态原因码，但必须保留原必填字段与原枚举值；启用后不兼容变化必须发布 V2。

新版本：删除/重命名、必填新增、单位变化、enum 语义变化、subject/key 变化、敏感级别变化。

v1/v2 可并行发布；消费者声明支持版本；删除旧版本需 release 记录。

## 5. Kafka 约定（Planned full profile）

当前 core 使用 `platform_event.event_publication`、本地 dispatcher 与 Consumer Inbox 完成可靠模块协作，不包含 Kafka adapter。Kafka broker ack 与本地 Consumer 完成是不同状态，未来接入时不得混用。

- key：tenant + subject type/id；
- headers：eventId/type/schemaRef/correlation/trace context（不含敏感）；
- producer acks/重试由锁定配置；
- 消费者至少一次；
- schema validation 在 producer 和 fixture tests；
- topic retention 为 demo 配置，不作为业务审计唯一保存。

## 6. 错误事件

业务失败发布事实（如 ReservationFailed）而不是 Java exception；技术处理失败保留在 publication/inbox 状态和运维指标，不对每次异常广播一个业务事件。

## 7. 示例与 fixtures

每个公开事件必须有：

- JSON Schema；
- valid example；
- 至少一个 consumer fixture；
- 敏感字段审查；
- requirement/producer/consumer 映射。
