# 模块依赖规则

## 1. 规则总览

模块只能通过：

1. 公开模块 API；
2. 显式 named interface；
3. 版本化事件；
4. 受控的 read model/query contract；

进行协作。禁止依赖其他模块 `internal`、Repository、JPA Entity、迁移或表。

## 2. 允许依赖矩阵

`→` 表示源可依赖目标的公开契约；事件消费者不代表编译依赖于实现。

| Source | IAM | Partner | Catalog | Inventory | Quotation | TradePlanning | Order | Fulfillment | Exception | Settlement | Audit |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| IAM | — |  |  |  |  |  |  |  |  |  | →event |
| Partner | →security context | — |  |  |  |  |  |  |  |  | →event |
| Catalog | → |  | — |  |  |  |  |  |  |  | →event |
| Inventory | → |  | →SkuRef contract | — |  |  |  |  |  |  | →event |
| Quotation | → | →snapshot API | →snapshot API |  | — | →evaluate API |  |  |  |  | →event |
| TradePlanning | → | →eligibility snapshot | →SKU attributes | →availability query |  | — |  |  |  |  | →event |
| Order | → |  |  |  | →accepted event contract |  | — |  |  |  | →event |
| Fulfillment | → |  |  | →reservation event |  | →route/template code | →order event | — |  |  | →event |
| Exception | → |  |  | public recovery API |  |  | public recovery API | public recovery API | — |  | →event |
| Settlement | → |  |  |  |  |  | →event/snapshot | →event |  | — | →event |
| Audit | → |  |  |  |  |  |  |  |  |  | — |

精确依赖由代码和 `ApplicationModules.of(...).verify()` 生成文档校验。

## 3. 公开包

每个模块根包可包含：

- `*ModuleApi`：命令/查询 facade；
- `events`：稳定模块事件；
- `types`：极少量公开 DTO/ID；
- `package-info.java`：Modulith metadata；
- named interface 注解声明的公开包。

其他实现全部在 `internal`。禁止公开 JPA 注解实体。

## 4. 同步与异步选择

同步调用适用于：

- 当前用例必须立即得到结果；
- 不跨越长时间或外部系统；
- 目标模块提供稳定、窄的查询/策略 API；
- 不造成循环依赖。

事件适用于：

- 已发生事实通知多个消费者；
- 后续工作可最终一致；
- 需要崩溃恢复和重放；
- 不需要消费者同步回应。

禁止用事件伪装需要即时一致的验证，也禁止同步链路跨越 4~5 个模块形成分布式单体。

## 5. 循环防护

允许事件上的业务反馈，但不得形成编译循环。例如：

- Order 发布创建事件 → Inventory；
- Inventory 发布预占结果 → Order；

两者通过共享在 `contracts`/公开事件中的 schema 或各自事件类型协作，不互相引用 internal。应用编译依赖图仍需无环或仅通过明确 contract 模块打破。

## 6. 共享内核

允许的 `shared-kernel` 需极小且稳定：

- `TenantId`；
- `ActorRef`；
- `CorrelationContext`；
- `Money`/`Currency` 可作为通用值对象，但业务定价规则不共享；
- 技术错误 envelope/分页 contract。

新增共享概念需 ADR 或架构评审。Customer、SKU、Order、Status、Address 等领域对象不得全局共享。

## 7. 架构测试示例

```text
- noClasses().that().resideInAModule("quotation")
  .should().dependOnClassesThat().resideIn("inventory..internal..")
- domain packages must not depend on Spring MVC, JPA, Kafka or web DTO packages
- repositories are only accessed from their own module application/infrastructure
- controllers depend on application interfaces, never repositories
- modules must pass Spring Modulith verification
```

实际测试在实现后成为 CI 必须项。

## 8. 例外流程

任何临时跨边界访问：

1. 必须说明业务阻塞；
2. 提供至少两个替代方案；
3. 建立 ADR 和移除日期；
4. 增加自动化测试防止范围扩大；
5. README 不得将技术债描述为正式架构。
