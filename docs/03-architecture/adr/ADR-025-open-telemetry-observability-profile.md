# ADR-025：以 OpenTelemetry 与 Micrometer 构建可观测运行剖面

- 状态：Accepted
- 日期：2026-07-22
- 关联：ADR-001、ADR-007、ADR-013

## 背景

现有模块化单体已经通过数据库 publication 与 consumer inbox 提供可靠本地事件处理，但 HTTP、事件、调度任务和模拟外部适配器缺少统一 trace。Task 13 要求在不改写业务状态机、不引入消息中间件的前提下补齐可观测证据。

## 决策

1. Spring Boot 使用 Micrometer Observation、Micrometer Tracing OpenTelemetry bridge 与 OTLP exporter。HTTP 由框架观测；Repository 调用只记录 bounded class/method，不记录 SQL/绑定/结果；事件发布、消费、调度任务和模拟适配器在稳定业务边界显式观测。
2. 继续使用 ADR-013 的 PostgreSQL publication/inbox。生产者将 W3C `traceparent`/`tracestate` 写入事件 metadata；异步消费者创建独立 consumer span，并以 span link 连接生产上下文，避免伪造同步父子关系。
3. correlation、causation 与 event identity 只作为 trace/log 字段，不作为 metric label。业务指标只使用受控的 stage、outcome、action、job、event type 等低基数枚举。
4. 控制台使用 ECS 结构化 JSON，包含服务版本以及框架提供的 trace/span 与 MDC correlation 字段。HTTP observation 保留低基数路由模板并移除原始 URL 属性，避免门户 capability token 进入 trace。禁止记录请求/响应 body、capability token、密码、商业成本/毛利和个人数据。
5. Full profile 使用 OpenTelemetry Collector 0.156.0、Prometheus 3.12.0、Grafana 13.1.0 与 Tempo 2.10.5。Tempo 使用单进程本地存储，仅用于可复现演示；不引入 Kafka、Redis 或日志存储服务。
6. Micrometer、OpenTelemetry 与 CycloneDX 工具使用 Apache-2.0 兼容依赖。Grafana/Tempo 作为未修改的独立上游运行容器，不链接进发行二进制；其 AGPL-3.0 义务由镜像自身上游说明覆盖。

## 后果

请求可以通过 correlation ID 定位结构化日志，并经事件 metadata 与 span link 追踪到异步处理。观测服务不参与业务正确性或可靠投递；其不可用不会改变领域事务。演示阈值、单节点存储和 100% sampling 都不是生产容量建议，生产部署必须重新评估 retention、sampling、访问控制和资源配额。
