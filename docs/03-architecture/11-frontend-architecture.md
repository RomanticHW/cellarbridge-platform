# 前端架构

## 1. 定位

React 前端是企业业务链路的可操作证明，不是单纯截图层。它必须正确处理授权、服务端状态、并发冲突、长流程状态、错误恢复和客户/内部视图差异。

## 2. 技术基线

- React 19.2 + TypeScript strict；
- Vite 8；
- React Router；
- TanStack Query；
- Ant Design；
- React Hook Form + Zod；
- ECharts（Task 12 Planned，当前未安装）；
- OpenAPI 生成 client/types；
- Vitest + React Testing Library；
- Playwright；
- 可选 MSW 用于组件/开发模拟。

不使用 Next.js：运营控制台和客户门户无 SSR/SEO 核心需求，Java 是服务端边界。

## 3. 应用边界

一个 SPA 包含两套路由 shell：

- `/app/*` 内部控制台；
- `/portal/*` 客户门户。

两者共享基础设计 token、错误解析和生成 API，但使用独立权限、layout 和 response schema。若后续安全/发布需要，可拆两个 build，当前保持单 repo 简化。

## 4. 状态管理

| 状态 | 所有者 |
|---|---|
| API 数据、缓存、刷新 | TanStack Query |
| 表单临时状态 | React Hook Form |
| URL 筛选/分页 | Router search params |
| 认证主体 | OIDC adapter/context |
| UI 短状态（抽屉等） | component state |
| 跨页客户端状态 | 只有明确需要才引入 Zustand |

禁止把服务端实体复制到 Redux/Zustand 再手工同步。

## 5. API 层

- `contracts/openapi` 生成类型；
- 统一 fetch client 注入 base URL、token、trace/correlation header；
- 标准 Problem Details 解析；
- 401 触发受控重新认证，403 显示权限；
- 409/412 显示冲突恢复；
- 429/503 按 `Retry-After` 提示，不无限自动重试写命令；
- Query key 按 tenant/feature/id；
- mutation 成功后精确失效/更新，不清空全部缓存。

## 6. Feature 结构

每个 feature：

```text
features/quotations/
├── api/
├── components/
├── pages/
├── forms/
├── hooks/
├── model/       // UI-specific view model only
├── tests/
└── routes.tsx
```

业务术语与后端一致，但前端不复制聚合内部实现。

## 7. 权限和 Allowed Actions

- 路由守卫用于 UX；
- 按钮权限从本地 permission + 后端 `allowedActions` 组合；
- 后端最终校验；
- 隐藏敏感字段使用专用 API schema；
- 不能通过前端 feature flag 暴露高危 endpoint。

## 8. 表单

- Zod 执行即时格式校验；
- 服务端返回字段错误映射到表单；
- 复杂业务规则以服务端为准；
- 报价编辑自动保存只保存草稿，不自动提交；
- 离开未保存提示；
- 金额输入避免 JS 浮点计算，显示/编辑使用字符串或 decimal library，最终由服务端计算；
- expectedVersion/ETag 随 mutation。

## 9. 长流程更新

P1 优先 polling + Query refetch，简单可靠。若引入 SSE：

- 仅用于状态通知，不作为唯一事实；
- 断线后重新查询；
- tenant/permission 校验；
- 不为炫技引入 WebSocket。

## 10. 设计系统

- Ant Design 作为基础；
- 建立 `StatusTag`, `MoneyText`, `BusinessNumberLink`, `ProblemAlert`, `AuditTimeline`, `AllowedActionBar` 等业务通用组件；
- 统一状态文案和颜色，但不只依赖颜色；
- 页面密度适合运营后台；
- 不使用过度动画或营销式卡片破坏专业性。

## 11. 测试

- 单元：格式化、权限和错误映射；
- 组件：表单、路径解释、审批动作、冲突状态；
- 集成（MSW）：API happy/error/forbidden；
- E2E：已交付登录、客户审核、报价、接受和订单；预占、履约异常、付款随对应纵向切片交付；
- axe/键盘检查；
- Playwright trace 保留失败证据。

## 12. 性能

- route-level lazy loading；
- 大表服务端分页/虚拟化仅有证据时；
- ECharts 引入后按页面加载；
- 避免重复请求/N+1；
- bundle 分析进入 release；
- Web Vitals 记录但运营 SPA 不夸大 SEO 指标。
