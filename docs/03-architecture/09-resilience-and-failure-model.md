# 韧性与故障模型

## 1. 原则

- 先确保状态不被破坏，再追求自动恢复；
- 每个外部边界有超时、幂等和失败分类；
- 重试必须有上限；
- Circuit breaker 只在真实远程依赖出现时使用；
- 失败对用户可见为明确状态，不返回虚假成功。

## 2. 组件故障矩阵

| 故障 | 影响 | 系统行为 | 恢复 |
|---|---|---|---|
| PostgreSQL 不可用 | 所有写/大部分读 | readiness false，503，不缓存写 | DB 恢复后重试安全命令 |
| Keycloak 不可用 | 新登录/刷新失败 | 已有有效 JWT 在验证可用时继续；不绕过认证 | IDP 恢复 |
| Redis 不可用（当前未部署） | 无运行时影响 | Core 正确性不依赖 Redis；profile 验证拓扑和依赖中不存在 Redis | 若以后引入，需新增降级与恢复证据 |
| Kafka 不可用（当前未部署） | 无运行时影响 | 当前使用 PostgreSQL local publication/Inbox，并验证积压、重复与恢复 | 若以后引入 external outbox，需新增 broker outage 证据 |
| OTEL/Prometheus 不可用（Planned） | 可观测性下降 | 业务不失败；导出有界缓冲 | 恢复后继续 |
| 模拟外部适配器超时（demo only） | 履约步骤失败 | 持久化 `SIMULATED_ADAPTER_TIMEOUT`，进入异常且不返回虚假成功 | 源状态验证后幂等重试/恢复 |
| 应用进程崩溃 | 处理中断 | DB 事务回滚；已提交 publication 重启恢复 | 重启/多实例 |

## 3. 超时与重试

- 数据库由 driver/pool 设置合理超时；
- HTTP 适配器区分 connect/read/overall timeout；
- 仅幂等 GET 或携带幂等键命令自动重试；
- 指数退避 + jitter；
- 最多次数和总预算；
- 用户请求线程不等待长时间业务流程；
- retry count 和原因可观测。

## 4. Circuit Breaker

P1 只在模拟外部适配器中演示：

- failure threshold；
- open duration；
- half-open probes；
- fallback 不是伪造成功，而是标记步骤等待/异常；
- 状态指标。

内部模块调用不使用 circuit breaker，因同进程失败应直接暴露。

## 5. Bulkhead

- 外部适配器使用独立线程/连接池；
- event consumer 并发按类型限制；
- 报表重建不占满交易连接池；
- 大导出/种子任务受限；
- full profile 资源限制写入 compose。

## 6. Poison Message

- schema 校验失败直接 FAILED_FINAL；
- 处理业务不变量失败进入异常而非无限重试；
- 保存安全错误摘要、event ID、consumer；
- 原 payload 受权限访问；
- 重放不能编辑原事件；修正使用新事件。

## 7. 模糊外部结果

若请求超时但外部可能成功：

1. 不立即重复非幂等调用；
2. 使用外部 idempotency key 查询状态；
3. 若无法查询，步骤进入 UNKNOWN/异常；
4. 人工确认需证据和审计；
5. 不把 UNKNOWN 当 FAILED 或 COMPLETED。

## 8. 故障注入

自动化测试提供受控 fault fixture：

- publication backlog/duplicate 与 consumer 事务失败；
- adapter delay/failure/timeout；
- 真实 PostgreSQL deadlock 与有界重试；
- projector duplicate/out-of-order/rebuild；
- Keycloak 暂停、JWK cache 验证与恢复；
- clock 前移触发逾期。

适配器非成功场景仅 demo profile 开启，并继续受 `FULFILLMENT_OPERATE` 权限保护；其他注入存在于测试或隔离 Compose profile，生产配置不暴露管理端点。详细证据见 `docs/evidence/resilience/report.md`。

## 9. 对账与 stuck 检测

为每个跨模块阶段定义期望最大延迟。超时创建 anomaly，不直接改状态。系统操作员页面显示：源状态、期望后继、最后事件、重试和建议动作。
