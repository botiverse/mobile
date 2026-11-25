---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

当 Agent 因服务商用量上限无法回复且后台上报了具体模型或限额窗口时，状态文案会直接标明受限模型、窗口类型（如 5 小时、每周、每周 Opus/Sonnet、额外用量）与服务商上报的重置时间，并在重置时间已过但等待尚未结束时回退显示等待结束时间。

## en

When an agent is held on a scoped provider usage limit, status surfaces now name the affected model, limit window (5-hour, weekly, weekly Opus/Sonnet, or extra usage), and provider-reported reset time—falling back to the hold wait end if the reported reset has already passed.

## ja

エージェントがプロバイダーの利用上限で応答できない場合、モデルや上限ウィンドウ（5 時間、週間、週間 Opus/Sonnet、追加利用）とプロバイダー報告のリセット時刻をステータスに表示するようになりました。報告されたリセット時刻を過ぎて待機が続いている場合は待機終了時刻へフォールバックします。
