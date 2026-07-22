# 性能与韧性证据运行手册

Status: **Available in Task 14**

## 1. 前置条件

- Java 21、Python 3.12、Docker/Docker Compose；
- full profile 另需 Node.js 24、pnpm 11 和 Chromium；
- Docker 至少分配与 `result.json` 中记录相当的 CPU/内存；
- 工作区可脏跑用于开发诊断，但发布证据必须在干净、已提交 revision 上执行。

## 2. 执行

```bash
make performance-smoke
make performance-full

# 等价的显式入口
./scripts/performance_evidence.sh smoke
./scripts/performance_evidence.sh full
```

PR quality gate 调用 smoke profile。GitHub Actions 的 **Performance and resilience evidence** 可手动选择 `full`；full 增加 Keycloak/JWK cache 暂停与恢复，不把该破坏性场景放入普通并行 E2E。

## 3. 结果读取顺序

1. `target/performance-evidence/<profile>/result.json`：revision、dirty 状态、环境、profile、总状态；
2. `dataset/manifest.json`：product、SKU、lot、partner、quotation、order/event 的精确行数，以及 seed、生成器/profile 版本与每个 gzip 的 SHA-256；
3. `catalog-search/summary.json`：p50/p95/p99、吞吐、错误率、基数与索引断言；
4. `route-evaluation.json`：纯策略 warmup/iterations、分位数、吞吐与推荐结果；
5. `correctness-scenarios.log`：JUnit、真实 PostgreSQL deadlock、事件 backlog 原始输出；
6. full 的 `keycloak-outage.json`：缓存 token、IDP 不可用、恢复后新 token 三阶段断言。

任一预期测试方法未执行、SQL 计划未使用单位索引、fixture 基数漂移、事件 metrics 缺失或 Redis 被引入而场景未更新时，运行器失败且不生成成功结果。

## 4. Profile 选择

| Profile | 用途 | 数据与负载 |
|---|---|---|
| smoke | PR、日常回归，目标 10 分钟 | 20,000 event 合成集、500-product SQL、5,000 route iterations、100-event duplicate backlog |
| full | main/nightly/release 指定运行，目标 30 分钟 | 1,000,000 event 合成集、15,000 SKU SQL、100,000 route iterations、1,000-event duplicate backlog、Keycloak outage |

首次镜像拉取、依赖下载和镜像构建不属于业务性能指标；仍由 workflow 的 40 分钟硬超时约束。`withinProfileBudget` 记录 harness 自身观测结果，超出时必须在结论中解释环境噪声或回归。

## 5. 结果判定

- 先看所有 correctness scenario 是否通过，再读延迟/吞吐；
- smoke 不能替代 15,000 SKU full 规模结论；
- 只将相同 profile、数据 hash、JVM/PostgreSQL、容器配额相近的运行作 before/after；
- 算法、SQL 或索引改动必须保留 before/after 的 `result.json` 和计划，并补普通回归测试；
- 不把本机 warm-cache 数值写成生产 SLA；
- Keycloak、Redis、broker 结论必须匹配实际拓扑：当前无 Redis/Kafka。

## 6. 故障控制边界

履约 `DELAY`/`FAILURE`/`TIMEOUT` 只在 demo 配置启用，且仍要求 `FULFILLMENT_OPERATE` 权限。full profile 通过隔离 Compose project 暂停 Keycloak，`finally` 恢复服务，脚本退出时删除该 project 和 volume。该过程不操作常用 `cellarbridge-core` project。

## 7. CI 工件

workflow 无论成功失败都上传 `performance-resilience-<profile>`，保留 14 天。失败时从生成日志、correctness log、SQL plan 或 Playwright trace 定位；不得用无条件重跑掩盖不稳定。公开报告见：

- `docs/evidence/performance/report.md`
- `docs/evidence/resilience/report.md`
