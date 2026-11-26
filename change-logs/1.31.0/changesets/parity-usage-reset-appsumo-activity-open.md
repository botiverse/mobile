---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

- 修复运行配额重置时间为 1970 占位时间戳时显示为异常过去时间或「稍后重置」的问题，现将其按无重置时间处理。
- 修复 iOS「方案与账单」页面在 AppSumo 终身方案的无限 Agent 配额下显示 `-1` 上限比例条的问题，现以本地化无上限文案展示已用数量。
- 修复从 Activity 打开已读（或无首条未读回复）的主题和任务会话时错误定位到不可读最新记录的问题，现直接打开至最新回复且不额外高亮旧消息。

## en

- Fixed runtime quota cards and compact lines rendering placeholder 1970 reset timestamps as bogus past times or "later"; pre-2000 reset timestamps are now treated as absent.
- Fixed the iOS Plan & Billing view showing a `-1` ratio bar for AppSumo lifetime tiers with unlimited agents; unlimited allowances now display localized unlimited usage text.
- Fixed opening a read (or first-unread-less) Activity thread or task conversation row focusing `latestActivityMessageId`, so read rows now open cleanly at the latest replies without jumping to an unreadable system line.

## ja

- ランタイム使用量のリセット時刻に 1970 年のプレースホルダーが返された場合に誤った過去時刻や「後で」と表示される不具合を修正し、2000 年以前の時刻は未設定として扱うようにしました。
- iOS の「プランと請求」画面で AppSumo 永久プランの無制限エージェント枠が `-1` の割合バーとして表示される不具合を修正し、ローカライズされた上限なしの使用状況テキストを表示するようにしました。
- Activity から既読（または最初の未読返信がない）スレッドおよびタスク会話を開いた際に `latestActivityMessageId` へフォーカスしていた不具合を修正し、最新の返信位置できれいに開くようにしました。
