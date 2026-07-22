# 技术基线

## 1. 冻结日期与原则

冻结日期：2026-07-13。版本来自各项目官方发布页面，并在仓库初始化时再次验证兼容性和可获得性。若任一组合存在兼容问题，必须通过 ADR 调整，不得静默改版本。

## 2. 后端

| 技术 | 基线版本 | 状态 | 用途 | 选择理由 |
|---|---|---|---|---|
| Java | 21 LTS | Available | 语言/JVM | 成熟 LTS、现代并发/语言能力、企业接受度 |
| Spring Boot | 4.1.0 | Available | 应用框架 | Actuator、配置、测试生态 |
| Spring Modulith | 2.1.0 | Available | 模块发现、验证和模块测试 | 执行模块化单体边界；不作为当前事件 publication registry |
| Spring Security | 与 Boot BOM | Available | OIDC Resource Server/授权 | 避免手写 token 安全 |
| Spring JDBC | 与 Boot BOM | Available | 当前 SQL-first 聚合、事件、查询与条件更新 | SQL 语义透明 |
| Spring Data JPA | 与 Boot BOM | Planned / optional | 未来有证据的简单聚合 | 当前未安装；引入条件见 ADR-012 |
| Flyway | Boot compatible | Available | 数据库迁移 | 版本化、可审阅 |
| Maven Wrapper | 锁定 | Available | 构建 | Java 企业常见、审阅友好 |

Java 21 而非更新非 LTS：作品核心是稳定工程；Boot 4 需要在 Task 01 验证 Java 最低要求和依赖兼容。若 Boot 4 生态依赖阻塞，备选是受支持的 Boot 3.5.x，但必须 ADR 记录。

## 3. 数据与基础设施

| 技术 | 基线版本 | 状态 | 用途 |
|---|---|---|---|
| PostgreSQL | 18.4 | Available | 交易数据库、全文/模糊搜索 |
| Keycloak | 26.7.0 | Available | 本地 OIDC 身份提供方 |
| Redis | 8.8 | Planned full profile | 安全读缓存/限流辅助，不参与正确性 |
| Apache Kafka | 4.3 | Planned full profile | 外部事件流 |
| OpenTelemetry Collector | 0.156.0 | Available full profile | vendor-neutral trace pipeline |
| Tempo | 2.10.5 | Available full profile | 单节点演示 trace backend |
| Prometheus | 3.12.0 | Available full profile | 演示指标与告警规则 |
| Grafana | 13.1.0 | Available full profile | 版本化演示看板与 trace 查询 |

容器 image 必须锁定 patch/tag，并在 release 可选择 digest。数据库主版本变化需迁移/驱动/Testcontainers 验证。

## 4. 前端

| 技术 | 基线 | 状态 | 用途 |
|---|---|---|---|
| Node.js | 24 LTS | Available | 工具运行时 |
| pnpm | 仓库锁定 | Available | 包管理 |
| React | 19.2 | Available | UI |
| TypeScript | 兼容稳定 | Available | 类型安全 |
| Vite | 8.1.4 | Available | 构建/开发服务器 |
| React Router | 兼容稳定 | Available | 路由 |
| TanStack Query | 兼容稳定 | Available | 服务端状态 |
| Ant Design | 兼容 React 19 的稳定版本 | Available | 企业 UI |
| React Hook Form + Zod | 兼容稳定 | Available | 表单/客户端校验 |
| ECharts | 待引入 | Planned（Task 12） | 业务看板 |
| Vitest/Testing Library/Playwright | 锁定稳定 | Available | 测试 |

具体 minor/patch 在 `package.json` 和 lockfile 冻结；不用 `latest` 浮动。

## 5. 契约和质量工具

- OpenAPI 3.1；
- AsyncAPI 3.x（以工具兼容性锁定）；
- JSON Schema 2020-12；
- RFC 9457 风格 Problem Details；
- ArchUnit；
- Testcontainers；
- Spotless/Checkstyle（选择一套明确格式策略）；
- SpotBugs/NullAway/Error Prone 根据 Boot 4/Java 21 兼容验证；
- markdownlint、Spectral/Redocly 等契约工具；
- Trivy/Grype、Syft/CycloneDX 任选稳定组合。

## 6. 官方来源

- OpenJDK 21: https://openjdk.org/projects/jdk/21/
- Spring Boot: https://spring.io/projects/spring-boot
- Spring Modulith: https://spring.io/projects/spring-modulith
- PostgreSQL: https://www.postgresql.org/
- React: https://react.dev/
- Vite: https://vite.dev/
- Node release schedule: https://nodejs.org/en/about/previous-releases
- Keycloak downloads: https://www.keycloak.org/downloads
- Apache Kafka: https://kafka.apache.org/
- Redis: https://redis.io/
- OpenTelemetry: https://opentelemetry.io/
- Testcontainers: https://testcontainers.com/

## 7. 升级策略

- Dependabot/Renovate 提出升级 PR；
- patch 可在 CI 全绿后合并；
- minor/major 检查 release notes、兼容性和架构影响；
- 数据库/身份/Kafka major 必须 ADR；
- 更新 `design-baseline.yaml`、本文、lockfile 和验证报告；
- README 不列未经实际构建验证的版本。
