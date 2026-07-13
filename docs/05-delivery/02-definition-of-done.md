# 完成定义（Definition of Done）

## 1. 任务完成

一项实现任务只有同时满足以下条件才为 Done：

### 产品和设计

- 需求/AC 有明确引用；
- 行为与状态机/不变量一致；
- 设计变化已更新文档/ADR；
- out-of-scope 未被暗中实现；
- UI 包含 loading/empty/error/forbidden/conflict。

### 代码

- 边界清晰，无跨模块 internal/table；
- 无无意义通用 abstraction；
- 领域方法表达行为，无 public setters 绕规则；
- 金额/时间/ID 使用规范；
- migration 追加且可执行；
- generated code 可重现；
- 无 secret/private data。

### 测试

- 新规则有领域测试；
- persistence/SQL 有 PostgreSQL integration；
- API auth/validation/error；
- 事件消费者重复测试；
- 关键 UI 测试；
- 指定 E2E；
- tenant/field security（相关）；
- concurrency/idempotency（相关）；
- 没有禁用/弱化既有测试。

### 契约与文档

- OpenAPI/AsyncAPI/schema 同步；
- examples validate；
- README 状态准确；
- implementation-status 更新；
- ADR/changelog/release notes（相关）；
- screenshots 使用合成数据。

### 运行和安全

- format/lint/type/compile；
- architecture verify；
- unit/integration；
- compose smoke；
- secret/dependency scan；
- 日志无敏感值；
- health/metrics 正常。

### Git

- commits coherent、Conventional Commits；
- PR 描述完整；
- 工作树干净；
- 不 force push shared history；
- 最终报告包含实际命令和结果。

## 2. Story Done vs Release Done

Story Done 不等于公开可用。Release Done 额外要求：

- 主 demo 全流程 Playwright；
- 新环境一键启动验证；
- 性能/并发/故障报告；
- SBOM/镜像扫描；
- demo data/reset；
- README 双语和截图；
- reviewer path；
- tag/release；
- 无 private control materials。

## 3. 不可接受的“完成”

- “代码已生成但未运行”；
- “单测通过”但未真实执行；
- Swagger 有 endpoint 但业务为空；
- 前端只静态 mock；
- 使用 H2 证明 PostgreSQL 并发；
- 通过 catch Exception 返回成功；
- 直接改 DB 修复 demo；
- README 写完成功但功能 Designed；
- 将技术债藏在未追踪注释。
