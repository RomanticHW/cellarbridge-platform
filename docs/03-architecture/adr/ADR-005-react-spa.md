# ADR-005：采用 React TypeScript SPA

- 状态：Accepted
- 日期：2026-07-13

## 决策

使用 React 19.2、TypeScript、Vite、React Router、TanStack Query 和 Ant Design。内部控制台与客户门户在一个 SPA 中以独立路由/权限边界实现。

## 理由

产品是登录后的企业操作系统，无 SSR/SEO 核心需求；Java 保持唯一服务端业务边界。

## 拒绝方案

Next.js 会引入第二服务端边界；Vue 不是本次学习/展示目标。
