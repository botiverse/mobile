---
type: changed
area: members
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

Agent 资料页的状态会用更清楚的话说明启动失败的原因：账号或套餐不包含所选模型、启动后反复以同样方式失败、上下文太大，以及启动超时。自动唤醒暂停时会显示 Raft 下次自动重试的时间。模型不可用或上下文太大时，有权限的人可以直接点「更换模型」打开模型配置。

## en

The agent profile's status now explains a failed start in plain words: the account or plan doesn't include the model, the agent kept failing the same way after starting, the context is too large, or the start timed out. When automatic wakes are paused, it says when Raft tries again by itself. When the model can't be used or the context no longer fits, people who can configure the agent get Change model, which opens its model settings.

## ja

エージェントのプロフィールのステータスで、起動に失敗した理由がわかりやすく表示されるようになりました（アカウントやプランにモデルが含まれていない、起動後に同じ失敗を繰り返した、コンテキストが大きすぎる、起動がタイムアウトした）。自動起動が一時停止中は、Raft が次に自動で再試行する時刻が表示されます。モデルが使えない場合やコンテキストが収まらない場合、エージェントを設定できる人には「モデルを変更」が表示され、モデル設定を開けます。
