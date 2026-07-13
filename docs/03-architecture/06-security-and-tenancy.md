# 安全与多租户

## 1. 威胁边界

主要资产：客户资料、报价价格、内部成本/毛利、库存、订单、付款记录、审计证据、身份令牌。

主要威胁：跨租户读取、水平/垂直越权、客户门户 token 泄露、日志泄露、重复命令、恶意输入、依赖漏洞、演示管理接口暴露、事件伪造。

本设计不声称替代正式生产威胁建模，但在实现阶段维护简化 STRIDE 清单。

## 2. 认证

### 内部控制台

- OIDC Authorization Code + PKCE；
- React 不保存长期 refresh token 于 localStorage；具体使用安全 OIDC client 方案；
- 后端验证 issuer、audience、签名、exp/nbf；
- JWK 缓存遵循库行为并可观测错误；
- Keycloak 仅为本地身份提供方，业务权限映射在 CellarBridge。

### 客户门户

优先同一 OIDC realm 的 customer client/role。为无账号演示可使用短期一次/受控 token，但必须：

- 高熵、哈希存储；
- 有过期、撤销和用途绑定；
- 绑定报价/客户和允许动作；
- 不写日志、URL analytics 或 referrer；
- 接受动作仍要求幂等和状态校验。

## 3. 授权模型

采用 RBAC + ABAC：

- RBAC：稳定权限码；
- ABAC：tenant、owner/assignee、partner、状态、仓库范围、字段分类；
- 角色只是权限集合模板，代码不硬编码“管理员什么都能做”。

例：审批报价需要 `quotation:approve`、同租户、不是提交人（或合规豁免）、报价处于待审批、字段权限允许查看决策数据。

## 4. 租户隔离

P1 采用共享数据库、共享 schema 表、每行 `tenant_id`，同时按模块 schema 所有权。

防线：

1. JWT/本地映射确定 TenantContext，不信任请求 body/header 任意 tenant；
2. Repository/SQL 必须显式 tenant predicate；
3. 复合唯一键包含 tenant；
4. 缓存 key 包含 tenant；
5. 事件包含 tenant 并验证消费者上下文；
6. 双租户集成测试覆盖 ID 猜测、筛选、分页、导出、时间线；
7. 管理操作不能无意跨租户。

可选 hardening 使用 PostgreSQL RLS，但不作为 P1 唯一隔离机制；引入需 ADR 和连接池上下文验证。

## 5. 权限码

示例：

```text
partner:read, partner:create, partner:submit, partner:review
catalog:read, catalog:maintain
quotation:read, quotation:create, quotation:submit, quotation:approve, quotation:issue
quotation:read-commercial-sensitive
order:read, order:cancel
inventory:read, inventory:reserve, inventory:adjust
fulfillment:read, fulfillment:operate
exception:read, exception:assign, exception:recover
settlement:read, settlement:record-payment, settlement:reverse-payment
reporting:read, audit:read
event-publication:read, event-publication:replay
administration:manage-access
```

权限矩阵在 contracts 文档中为权威。

## 6. 字段级安全

分类：PUBLIC、CUSTOMER_VISIBLE、INTERNAL、COMMERCIAL_SENSITIVE、PERSONAL、SECURITY_SECRET。

- 客户 API 使用专用 response type，不靠序列化忽略动态字段；
- 成本/毛利仅授权内部角色；
- 审计 payload 记录字段名/摘要而非敏感原值；
- 日志默认不记录 request/response body；
- 个人信息展示掩码；
- token/secret 永不返回。

## 7. Web 安全

- CORS 严格 origin；
- CSP、frame-ancestors、nosniff、referrer-policy；
- CSRF 按 token 存储/浏览器架构评估；纯 bearer API 仍防 token 泄露；
- 限制上传类型/大小，P1 仅附件元数据或本地对象存储模拟；
- 查询排序字段白名单；
- 防止 CSV/公式注入（如提供导出）；
- 错误不返回堆栈/SQL；
- Rate limit 用于登录外部边界/公开 token，不能代替授权。

## 8. 秘密与供应链

- `.env.example` 只含占位；
- GitHub Actions 使用 secrets；
- secret scanning、Dependabot/Renovate（二选一）、SCA、SBOM；
- 容器非 root、最小基础镜像、固定 digest 策略在 release hardening；
- 第三方依赖检查许可证和维护状态；
- 不在公开仓库存储原始招聘截图或个人账号信息。

## 9. 安全测试

- 未认证/过期/错误 audience；
- 跨租户 ID、筛选、分页、关联资源；
- 客户访问内部字段；
- 销售自审；
- 仓库操作员访问未分配仓库；
- 重放接口越权；
- token 出现在日志/trace；
- 输入边界、SQL 注入由参数化查询保障并测试；
- 依赖和容器扫描。

## 10. 演示账户

README 可公开角色和用户名，但密码使用本地固定演示值仅在 demo profile，并明确非生产。生产 profile 禁止默认密码和演示重置接口。
