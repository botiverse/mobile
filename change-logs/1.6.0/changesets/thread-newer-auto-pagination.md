---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复从历史消息定位进入对话流后，向较新的消息浏览时必须反复点击「加载新消息」的问题。现在每次新的向下浏览手势会在接近窗口末端时自动加载一页，停手不会连续翻页；加载期间保持当前阅读位置，失败时仍可手动重试。

## en

Fixed Thread history windows requiring repeated “Load newer messages” taps while browsing toward recent messages. Each fresh toward-newer gesture now loads at most one page near the window boundary without cascading while idle, preserves the current reading position during the append, and keeps an explicit Retry action for failures.
