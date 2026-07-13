# 可观测性设计

## 1. 目标

可观测性用于回答：

- 哪个业务命令失败，为什么？
- 报价接受后订单/预占/履约走到哪里？
- 是否存在事件积压或重复？
- 库存预占冲突率和耗时如何？
- 哪些业务异常需要人员处理？
- 数据看板延迟多少？

不是为了展示 Grafana 截图而无差别采集。

## 2. 三类信号

### 日志

JSON 结构化字段：timestamp、level、service、module、useCase、tenantHash、actorType、businessNumber、traceId、spanId、correlationId、errorCode、outcome、durationMs。

禁止：访问令牌、密码、完整请求体、成本/毛利、客户门户 token、完整个人信息、事件完整敏感 payload。

### 指标

技术：

- HTTP latency/error；
- JVM/GC/thread；
- DB pool/query；
- event publication backlog/age/retry；
- Kafka lag；
- cache hit/eviction；
- scheduler duration/last success。

业务：

- quotation created/submitted/accepted；
- approval queue age；
- route rejection by reason；
- route override rate；
- order conversion idempotency hit；
- reservation success/failure/conflict；
- fulfillment step overdue；
- open exception by severity；
- receivable outstanding/overdue（合成数据）。

业务编号、客户名等高基数字段不作为 metric label。

### Trace

OpenTelemetry spans：

- HTTP request；
- application use case；
- database (library auto instrumentation + selective semantic spans)；
- event publish/consume；
- external adapter simulation；
- critical policy evaluation。

异步使用 trace links + correlation/causation，而不是伪造同步 parent。

## 3. Correlation 设计

- 入站 `traceparent` 合法则继续；
- 每个业务命令有 `commandId`；
- correlationId 贯穿客户接受到履约；
- causationId 指向直接触发命令/事件；
- 事件 envelope 保存两者；
- 审计时间线可按 correlation 搜索；
- 外部客户端提供的 correlation 仅作为候选，验证格式并防日志注入。

## 4. Dashboard

### Technical Overview

服务健康、API RED、JVM、DB、事件积压、Kafka lag、失败发布。

### Quote-to-Fulfillment

报价漏斗、审批时长、路径拒绝、转换延迟、预占成功、履约 SLA、异常。

### Inventory Correctness

预占请求、成功量、冲突重试、库存不足、SQL latency、异常约束次数（目标 0）。

### Event Reliability

pending oldest age、publish attempts、duplicates consumed、failed final、replay actions。

## 5. SLO（演示目标）

不作为真实生产 SLA：

- core API 成功率 > 99% 在受控演示压测；
- P95 见 NFR；
- pending publication oldest age < 60s 正常运行；
- audit projection lag < 10s；
- invariant violation metric = 0；
- failed-final events = 0 发布前。

## 6. 告警演示

- event backlog age；
- reservation failure surge；
- open critical exceptions；
- DB connection saturation；
- projection stalled；
- authentication failure spike。

告警路由到本地 Alertmanager/日志模拟，不连接真实人员渠道。

## 7. 数据保留与成本

- 开发日志短期；
- trace 采样，错误和主演示链路保留；
- metric label 低基数；
- 不将完整事件 payload 复制到日志；
- 审计业务记录与技术日志分开。

## 8. 验收

主 demo 可从一个订单页面复制 correlation ID，在 trace 中看到接受 → 订单 → 预占 → 计划；日志无敏感字段；故障注入后 backlog/重试/异常指标变化可解释。
