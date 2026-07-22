# 公开发布与仓库卫生

## 1. 双仓库策略

### 公开仓库 `cellarbridge-platform`

包含：README、产品/领域/架构/契约、代码、测试、合成数据、公开报告、CI、release assets。

### 私有仓库 `cellarbridge-project-control`

包含：内部实施 prompts、原始截图/转录、任务 ledger、HANDOFF、设备接力记录、未公开方案和临时分析。

**从第一天分离。** Public branch 同样公开；不能把私有文件放 hidden branch。

## 2. 为什么不建议“后面再删除历史”

Git 对象、fork、clone、cache 和平台日志可能保留已提交内容。普通删除只从当前树移除；历史重写会破坏 commit/tag/PR 链接并要求协作者重置。秘密一旦提交还必须立即轮换。

历史清理仅作为事故响应，不作为日常方案。

## 3. Public allowlist

允许：

```text
README/AGENTS/CONTRIBUTING/SECURITY/LICENSE
backend/frontend/contracts/docs/deploy/scripts
.github
synthetic fixtures and release evidence
```

禁止模式：

```text
prompts/private
research/raw
HANDOFF.md
.task-ledger*
*.env
credentials/tokens
IDE/user files
IMG_*.png raw research
real customer/product/price exports
internal execution transcripts
```

CI `public-repo-policy` 检查路径和秘密。

## 4. Pre-commit/CI

- gitleaks/trufflehog 选择；
- filename denylist；
- large file check；
- license/PII keyword review；
- git diff staged manual；
- README status；
- source images metadata strip；
- dependency lockfiles committed。

v1.0.0 额外维护 `docs/evidence/release/tracked-file-inventory.tsv`。每个 release-tree 文件必须
被归类为 source、test、contract、doc、generated、release_asset 或 evidence；
`scripts/generate_publication_inventory.py` 在 public validation 中阻止清单漂移。

## 5. 截图与媒体

- 只使用 synthetic data；
- crop 掉浏览器账号/通知；
- 清除 EXIF；
- 文件名描述页面/release；
- alt text；
- 不使用目标公司 logo；
- release screenshot 与 tag 匹配。

v1.0.0 截图由精确 tag 的 `make demo-e2e` 生成到 CI 工件，再由 release workflow 附加；
不把 Playwright trace、带 capability URL 的错误页或未经复核的二进制提交到源码树。

## 6. README 语言

中英双语自然专业；不写“AI generated”“炫技项目”等。强调 problem/decisions/evidence。状态真实，避免“enterprise-grade”“production-ready”无证明绝对词。

## 7. 事故流程

发现秘密/私人数据：

1. 停止推送/通知 owner；
2. 立即轮换/撤销秘密；
3. 评估 GitHub exposure/forks；
4. 使用官方 history rewrite 程序；
5. force push 需明确授权；
6. 通知所有 clone；
7. security incident note（敏感细节私有）；
8. 加防复发 gate。
