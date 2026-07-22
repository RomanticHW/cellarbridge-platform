# 可观测性设计

状态：**Available for the demonstration profile**。core 提供 Actuator/Micrometer、ECS JSON 日志和 OpenTelemetry export；full profile 增加 Collector、Tempo、Prometheus、Grafana、版本化 dashboard 与演示告警。设计决策见 ADR-025，操作证据见 `docs/evidence/observability/`。

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

控制台使用 Spring Boot ECS JSON。基础字段包含 timestamp、level、service/version、trace/span；MDC 提供 correlation、tenant-safe hash 和上下文字段，固定 module/errorCode 表明未显式设置时的安全默认。应用日志和审计 sanitizer 对凭据、body、成本/毛利与个人联系字段 fail closed。

禁止：访问令牌、密码、完整请求体、成本/毛利、客户门户 token、完整个人信息、事件完整敏感 payload。

### 指标

下列为目标指标目录，不表示当前已全部注册。Kafka、Inventory reservation、Fulfillment、Exception 和 Settlement 指标随对应能力交付。

技术：

- HTTP latency/error；
- JVM/GC/thread；
- DB pool/query；
- event publication backlog/age/retry；
- local Consumer Inbox retry/final failure；
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

状态：**Available**。

OpenTelemetry spans：

- HTTP request；
- application use case；
- database (library auto instrumentation + selective semantic spans)；
- event publish/consume；
- external adapter simulation；
- critical policy evaluation。

异步使用 trace links + correlation/causation，而不是伪造同步 parent。

## 3. Correlation 设计（Available）

事件 envelope 保存 correlation/causation 以及 W3C `traceparent`/`tracestate` metadata。入口 filter 建立固定名称的安全请求 span，Spring/Micrometer 补充低基数路由模板；自定义 observation convention 不导出原始 URL，防止门户 capability token 进入 trace。异步消费者建立独立 consumer span 并 link 生产上下文。浏览器验收固定 correlation header 并用数据库断言事件 trace metadata。

- 入站 `traceparent` 合法则继续；
- 每个业务命令有 `commandId`；
- correlationId 贯穿客户接受到履约；
- causationId 指向直接触发命令/事件；
- 事件 envelope 保存两者；
- 审计时间线可按 correlation 搜索；
- 外部客户端提供的 correlation 仅作为候选，验证格式并防日志注入。

## 4. Dashboard（Available）

版本化 `CellarBridge Operations` dashboard 当前覆盖：

- HTTP rate/status；
- publication backlog；
- projection lag；
- reservation outcomes；
- quotation lifecycle。

Prometheus 还暴露 oldest pending age、order conversion replay、reservation retry、fulfillment overdue、exception recovery、payment/reversal、open critical exception 和 scheduler outcome。标签只采用受控枚举或已注册 consumer 名称。

## 5. SLO（演示目标）

不作为真实生产 SLA：

- core API 成功率 > 99% 在受控演示压测；
- P95 见 NFR；
- pending publication oldest age < 60s 正常运行；
- audit projection lag < 10s；
- invariant violation metric = 0；
- failed-final events = 0 发布前。

## 6. 告警演示（Available examples）

- event backlog age；
- reservation failure surge；
- open critical exceptions；
- projection stalled；
- readiness failure。

告警路由到本地 Alertmanager/日志模拟，不连接真实人员渠道。

## 7. 数据保留与成本

- 开发日志短期；
- trace 采样，错误和主演示链路保留；
- metric label 低基数；
- 不将完整事件 payload 复制到日志；
- 审计业务记录与技术日志分开。

## 8. 验收

主 demo 用固定合成 correlation ID 证明浏览器请求、事件 correlation 与 W3C trace metadata 一致；Tempo walkthrough 说明如何沿 producer span 和 consumer link 检查异步链路。单元测试捕获脱敏日志、检查低基数标签并验证 event/scheduler trace 行为。
