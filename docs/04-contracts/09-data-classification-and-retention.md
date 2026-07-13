# 数据分类与保留

## 1. 分类

| 类别 | 示例 | 控制 |
|---|---|---|
| PUBLIC | README、合成产品描述 | 可公开 |
| CUSTOMER_VISIBLE | 客户自己的报价/订单/公开里程碑 | 身份+partner 边界 |
| INTERNAL | 工作项、一般运营状态 | 内部角色 |
| COMMERCIAL_SENSITIVE | 成本、毛利、信用、精确库存 | 专门权限、日志禁写 |
| PERSONAL | 联系人姓名、电话、邮箱、地址 | 最小化、掩码、审计 |
| SECURITY_SECRET | token、密码、client secret | 不持久化业务库/不日志 |
| TECHNICAL_SENSITIVE | stack、内部拓扑、失败 payload | System Operator 限制 |

## 2. 公开仓库

只允许：合成数据、抽象业务路径、公开技术配置样例。禁止：原始招聘截图、真实品牌 logo/客户/价格/库存、个人邮箱、真实凭据、内部 Prompt、设备接力记录。

## 3. 演示保留

| 数据 | 默认 |
|---|---|
| 交易/审计合成数据 | 保留至 demo reset |
| HTTP idempotency | 30 天 |
| Inbox | 90 天或长期摘要 |
| 技术日志 | 7~14 天本地 |
| Trace | 1~3 天/采样 |
| Metrics | 15~30 天本地 |
| 失败 publication | 解决后仍保留摘要 |
| 客户 portal token | 过期/撤销后短期摘要，原 token hash |

这些是演示默认，不宣称生产合规。

## 4. 删除/匿名化

- 主数据停用；
- 个人联系人可在无业务保留需求时匿名化，保留业务审计摘要；
- 交易快照中的法定必要字段生产需法律决定；演示不提供虚假“一键 GDPR”声明；
- audit 纠错使用追加；
- demo reset 整租户删除只在本地合成环境。

## 5. 日志/事件最小化

- 使用 ID/业务编号而非完整对象；
- tenant 使用 hash/低敏标识；
- request body 默认不日志；
- 事件 payload 审查分类；
- OpenTelemetry attributes 禁止高敏；
- 错误 detail 不回显秘密。

## 6. 数据访问

- 字段权限；
- 导出/敏感查看审计；
- 生产数据库最小权限（未来）；
- 备份继承数据分类；
- 私有控制仓库加密/访问限制由仓库托管方负责。
