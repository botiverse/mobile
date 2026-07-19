---
type: improved
area: qa-infra
platforms:
  - android
  - ios
  - ohos
pull_request: 0
rollout: full
---

## zh-CN

改进 Stamp 多端验收的证据契约：自动化验收结果现在会为每个稳定验收点保留 assertion id，使平台能够机械校验证据完整性，避免缺证据或执行器限制被误判为产品失败。

## en

Improved the Stamp cross-platform acceptance evidence contract by preserving a stable assertion id for every requirement, allowing the platform to verify evidence completeness mechanically instead of misclassifying missing evidence or harness limitations as product failures.
