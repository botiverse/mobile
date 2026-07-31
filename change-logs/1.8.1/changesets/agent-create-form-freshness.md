---
type: fixed
area: agents
platforms:
  - android
  - ios
  - ohos
pull_request: 1806
rollout: full
---

## zh-CN

打开创建 Agent 表单时现在会先加载最新的运行时契约，并在契约就绪前阻止提交。创建请求也不再发送空的可选字段，避免非 Built-in 或外部 Agent 被错误拒绝。

## en

The create-agent dialog now loads the latest runtime contract before allowing submission. Create requests also omit absent optional fields so non-Built-in and external agents are not rejected by an explicit null value.
