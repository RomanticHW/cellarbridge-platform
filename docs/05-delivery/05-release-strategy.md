# 发布策略

## 1. 版本

Semantic Versioning：

- `0.x`：实现里程碑，契约可能受控演进；
- `1.0.0`：主链路和工程证据完整；
- Design Baseline 独立 tag `design-baseline-v1.0`。

## 2. Release Candidate

`v1.0.0-rc.1` 至少满足：

- core/full compose；
- 主 E2E；
- 无高严重度已知安全问题（或公开有期限 waiver）；
- migrations/synthetic seed/reset；
- README status/screenshot；
- performance/concurrency/failure reports；
- public history hygiene scan。

v1.0.0 使用同一门禁但不创建缺少证据的中间 RC：release-candidate PR、合并后 `main` 与 annotated
tag workflow 分别验证候选、主干和精确发布提交。

## 3. Release Pipeline

1. checkout exact tag candidate；
2. docs/contracts validation；
3. backend/frontend all tests；
4. architecture/security；
5. build containers；
6. compose smoke/E2E；
7. concurrency/performance designated；
8. SBOM/image/dependency scan；
9. generate release evidence manifest/checksums；
10. human review README claims；
11. annotated tag/release notes。

## 4. Artifacts

- source tag；
- container images（可选 GHCR）；
- SBOM；
- test reports；
- API docs；
- demo screenshots/video/Playwright trace；
- release notes；
- checksums；
- known limitations。

v1.0.0 的小型附件由 `scripts/prepare_release_assets.py` 从 tag commit、浏览器证据和安全
workflow 工件装配。`release-manifest.json` 记录 commit/tree/兼容版本和单件 SHA-256，
`SHA256SUMS` 覆盖发布说明、证据、截图、SBOM 与 scan JSON。GitHub 自动 source archive
不重复上传。

## 5. 迁移兼容

每 release 记录 schema version。升级路径至少从前一个 minor/RC 验证。演示可重置，但不能用“重置”掩盖 migration 失败。

## 6. README 状态

发布前脚本/人工检查表将能力标记：Available/Partial/Designed/Planned。只有 release tests 有证据的功能写 Available。

## 7. 回滚

模块化单体容器可回滚，但 DB migration 采用向前兼容设计。Release notes 说明不能安全回滚的迁移。演示环境可从备份/seed 恢复，不宣称生产零停机。
