# ADR-006：OIDC 与 Keycloak

- 状态：Accepted
- 日期：2026-07-13

## 决策

本地使用 Keycloak 作为 OIDC Provider；浏览器采用 Authorization Code + PKCE；后端作为 JWT Resource Server。业务权限/租户映射在应用内维护。

## 后果

避免自建密码体系；增加本地依赖和 realm 配置维护。生产身份提供方可通过 OIDC 替换。
