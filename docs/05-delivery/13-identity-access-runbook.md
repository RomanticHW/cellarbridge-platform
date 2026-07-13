# 身份、租户与权限运行手册

Status: **Available in Task 02**

Requirements: **UC-IAM-001, FR-IAM-001–005**

## 1. 可观察行为

- React 控制台使用 OIDC Authorization Code + PKCE 登录；implicit flow 与 password grant 均关闭。
- 后端验证 JWT 签名、issuer、audience 与时间窗口，再用 `issuer + subject` 查询本地映射。
- `/api/v1/me` 仅返回公开用户标识、显示名、当前租户、显示角色和稳定权限码；不返回 token、外部 subject、内部映射 ID 或其他租户信息。
- 租户 claim 与本地映射冲突、用户/租户停用或未映射时，以稳定的 401/403 Problem 响应拒绝，不创建隐式租户。
- 控制台先解析 `/me`，再显示当前身份和租户；规划中模块的导航按权限码过滤。
- 除 Actuator 健康端点和 `/me` 外，Task 02 未实现的业务 endpoint 默认 deny。

## 2. 本地演示身份

这些身份和密码只存在于 `demo` profile 的版本化 Keycloak realm 与数据库 seed 中，均为合成数据。不得复制到生产配置；生产 profile 不含默认密码。

| Tenant         | Username         | Local role           | Password                  |
| -------------- | ---------------- | -------------------- | ------------------------- |
| North Cellars  | `north.sales`    | Sales Representative | `CellarBridge-Demo-2026!` |
| North Cellars  | `north.buyer`    | Customer Buyer       | `CellarBridge-Demo-2026!` |
| North Cellars  | `north.manager`  | Sales Manager        | `CellarBridge-Demo-2026!` |
| North Cellars  | `north.admin`    | Tenant Administrator | `CellarBridge-Demo-2026!` |
| Harbor Cellars | `harbor.manager` | Sales Manager        | `CellarBridge-Demo-2026!` |

`north.suspended` 仅用于拒绝路径验证。业务映射 seed 使用 `ON CONFLICT DO NOTHING`，重复启动不会覆盖后续修改。要恢复演示初始状态，只能清理本地 demo Compose volume 后重建。

## 3. 运行与验证

```bash
make dev-core
# open http://localhost:5173/app

make smoke-core
make identity-e2e
```

`make identity-e2e` 在隔离 Compose project 中启动 PostgreSQL、Keycloak、后端和前端，再用两个独立浏览器 context 登录两个租户。测试证明伪造 tenant header 不改变 `/me` 的租户，Tenant A 猜测 Tenant B 资源路径得到 403。

CI 不依赖外部身份服务或手工登录：JWT 验证测试使用进程内生成的 RSA 密钥；浏览器隔离测试使用仓库中的 realm import 和本地容器。

## 4. 租户上下文契约

- repository/query 的业务入口显式接收 `TenantId`，SQL 将 tenant predicate 放在 filter、排序与分页之前。
- 只有 tenant registry 的 `issuer + subject` 引导查询可标注 `GlobalRegistryAccess`；该查询只用于建立当前租户上下文。
- `TenantContextHolder` 是实例级、请求作用域生命周期的 ThreadLocal holder；过滤器结束时无条件清理。
- 线程池、虚拟线程或其他异步边界必须显式捕获 `TenantContextSnapshot` 并包装任务。上下文不会通过静态可变全局或隐式 inheritable thread local 传播。
- `AuthorizationService` 统一执行稳定 permission code 和目标 tenant 属性检查；controller 不依赖角色名称。

## 5. 威胁控制

| Threat         | Control and evidence                                                                                                 |
| -------------- | -------------------------------------------------------------------------------------------------------------------- |
| 伪造或篡改 JWT | Nimbus decoder 验证 JWK 签名、issuer、audience、expiry/not-before；测试覆盖错误 issuer/audience、过期和不受信签名    |
| 伪造租户       | 服务端只信已验证 token 与本地映射；header/body/query tenant 不参与上下文建立                                         |
| 跨租户查询     | 显式 `TenantId` repository contract、复合约束、tenant-first SQL 和双租户集成/浏览器测试                              |
| 权限绕过       | 默认 deny、统一 `AuthorizationService`、allow/deny 测试及 permission-aware navigation                                |
| token 泄漏     | SPA token 仅在 `sessionStorage`；不使用 `localStorage`；日志测试排除 JWT、Authorization header 和原始 subject/tenant |
| 会话过期       | 到期前 silent renew；到期后清除用户并显示重新登录提示；事件监听器卸载时清理                                          |
| 浏览器攻击面   | 精确 CORS origin、CSP、frame deny、nosniff、referrer 与 permissions policy 响应头                                    |

本 API 使用 Authorization bearer token，不使用 cookie 认证，因此 CSRF token 不作为当前 API 防线；如果以后引入认证 cookie，必须重新评估并启用相应 CSRF 控制。

## 6. 前端依赖决策

| Dependency           | Version | License    | Purpose and assessment                                                                            |
| -------------------- | ------: | ---------- | ------------------------------------------------------------------------------------------------- |
| `oidc-client-ts`     |   3.5.0 | Apache-2.0 | 标准 OIDC/OAuth browser client，支持 Authorization Code + PKCE、silent renew 和可配置 Web Storage |
| `react-oidc-context` |   3.3.1 | MIT        | 对 `oidc-client-ts` 的薄 React context adapter，提供成熟 session 生命周期集成                     |

曾评估直接使用 Keycloak JavaScript adapter：它能工作，但会让前端会话层绑定 Keycloak 特有 API，不利于保持标准 OIDC 边界。自行实现 PKCE、state、nonce、刷新与回调解析的安全风险更高，因此不采用。依赖均锁定精确版本和 lockfile；升级必须重新执行回调、过期、403、导航权限与真实 realm 浏览器测试。

## 7. 数据与兼容性

- `V2__identity_access_tenancy.sql` 新增 `identity_access` schema。tenant registry 是唯一明确的全局表；其他表包含 tenant、审计字段和 version。
- 用户映射和角色关联使用 tenant 复合键/约束；不建立跨模块外键。
- `R__identity_access_demo_seed.sql` 只在 `demo` profile 执行并采用非覆盖式重复 seed。
- OpenAPI `/me` 从设计契约变为已实现契约，并补充 403；响应结构没有破坏性变化。

## 8. 已知限制

- 运行时角色、用户和映射编辑不在 Task 02 范围；配置通过版本化 realm/seed 管理。
- 业务模块 endpoint 仍未实现并保持默认 deny。
- 当前 local realm 面向演示，不提供生产身份生命周期、密钥轮换编排或高可用部署。
