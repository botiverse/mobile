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

改进 Stamp 多端验收的证据契约：自动化验收结果现在会为每个稳定验收点保留 assertion id，并把 Android logcat 限定到本次运行观测到的应用 PID，使平台能够机械校验证据完整性，避免缺证据、跨进程崩溃噪声或执行器限制被误判为产品失败。

## en

Improved the Stamp cross-platform acceptance evidence contract by preserving a stable assertion id for every requirement and scoping Android logcat to app PIDs observed during the run, allowing the platform to verify evidence completeness without misclassifying missing evidence, cross-process crash noise, or harness limitations as product failures.
