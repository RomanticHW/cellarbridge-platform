# 演示脚本

## 1. 目标观众

Java 后端/全栈/架构岗位的技术面试官或用人团队。脚本重点证明工程判断和业务正确性，不追求展示所有菜单。

## 2. 15 分钟标准脚本

### 00:00–01:00 介绍

“CellarBridge 不是消费者商城，而是 B2B 酒饮报价到交付的协同平台。核心风险是复杂路径、重复下单、库存超卖和长流程失败。我选择模块化单体，保留本地事务与可运行性，同时用可靠事件协调模块。”

打开 README architecture 和运行中的 dashboard。

### 01:00–02:30 客户审核

- Sales 提交合成客户；
- Manager 批准路径/账期；
- 指出提交人与审批人、audit/correlation。

### 02:30–05:30 报价/路径

- 搜索 3 SKU；
- 加入报价；
- route evaluation：一条拒绝、两条评分；
- 非推荐覆盖 + 9% 折扣触发审批；
- Manager 看到 rule IDs 并批准。

### 05:30–07:30 客户接受/订单

- 客户 portal 不含成本/毛利；
- 双击接受/重放 API；
- 一个 acceptance、一个 order；
- 显示 idempotency replay/header/unique constraint test。

### 07:30–10:30 库存并发

- 展示订单预占；
- 运行 `make demo-concurrency`；
- 一个订单成功、另一个不足；
- 展示测试/DB invariant；
- 解释为什么不用 Redis lock。

### 10:30–12:30 履约异常

- plan steps/dependencies；
- 模拟 adapter timeout；
- exception 自动创建；
- recovery retry；
- customer public milestone 与 internal timeline 差异。

### 12:30–13:30 付款/看板

- 部分付款 → 全额；
- 展示冲正模型；
- dashboard 投影延迟。

### 13:30–15:00 工程证据

- Modulith/ArchUnit；
- OpenAPI/AsyncAPI；
- Testcontainers/concurrency；
- OTel trace；
- Git/ADRs；
- 一键 compose。

## 3. 30 分钟深挖

可选择：

- route policy determinism/property tests；
- quote snapshot/price rounding；
- outbox/inbox crash windows；
- inventory SQL/explain/deadlock prevention；
- tenant security matrix；
- projection rebuild；
- React server state/conflict handling；
- why not microservices/Elasticsearch/workflow engine。

## 4. 失败备用

- 保留 release commit 的 Playwright trace/screenshots；
- core profile，无 Kafka/Grafana；
- API examples；
- 不伪装 live；说明本地资源限制并展示测试报告。

## 5. 问答准备

每个亮点回答：业务问题 → 备选 → 决策 → 不变量/代价 → 测试证据 → 演进条件。
