# ADR-009：优先 PostgreSQL 搜索，不引入 Elasticsearch

- 状态：Accepted
- 日期：2026-07-13

## 决策

使用 PostgreSQL full-text、pg_trgm 和结构化索引支撑目录搜索。

## 理由

预期 SKU 规模和查询无需独立搜索集群；减少运维与一致性成本。

## 重新评估条件

索引/查询优化后仍无法满足数据规模、语言分析、相关性或独立扩展需求，并有基准证据。
