# Commit 与 Pull Request 策略

## 1. Conventional Commits

类型：`feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `build`, `ci`, `chore`, `security`。

示例：

```text
feat(quotation): add explainable route evaluation
feat(inventory): reserve lots with atomic conditional updates
test(inventory): prove no oversell under concurrent reservations
docs(adr): record PostgreSQL search decision
```

## 2. 一个任务的建议 commit 序列

1. `docs(...)`/`test(...)`：契约/验收或 failing test；
2. `feat(...)`：领域/应用；
3. `feat(...)`：持久化/API；
4. `feat(frontend)`：UI；
5. `test(...)`：集成/E2E；
6. `docs(...)`/`chore(...)`：状态、演示、质量。

不强制机械拆分；每个 commit 必须独立 coherent，尽量能构建。不要一个 100 文件“implement module”提交。

## 3. PR 大小

目标 300~800 行手写逻辑（不计 generated/migration/fixtures）或一个明确纵向切片。过大任务拆 PR，但中间不得把 main 留在虚假可用状态。使用 feature flag 只在确有需要。

## 4. PR 模板内容

- Why/业务价值；
- Scope/out-of-scope；
- Design/requirements/ADR；
- module/data/contract changes；
- security/tenant；
- migrations/compatibility；
- tests and commands actually run；
- screenshots/traces；
- risks/rollback/remaining；
- checklist。

## 5. Review 顺序

1. 业务规则与边界；
2. 安全和数据所有权；
3. 事务/并发/幂等；
4. 契约与错误；
5. 测试证据；
6. 可读性/风格。

避免先争论格式而忽略正确性。

## 6. Git 历史

- public main history不含 Prompt、原始截图、个人 note；
- 不提交 secrets 再删除；
- 不依赖未来 history rewrite 清理；
- accidental secret 立即 rotate，然后按安全程序 rewrite；
- ordinary messy internal planning留 private repo；
- release tags annotated。

## 7. 实施执行规则

每个实施任务必须指定 allowed paths、forbidden changes 和 commit plan。任何执行者不得未经授权 squash 已发布历史、force push，或创建、删除未确认的 remote。
