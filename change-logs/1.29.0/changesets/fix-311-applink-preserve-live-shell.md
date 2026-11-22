---
type: fixed
area: navigation
platforms:
  - android
pull_request: 5410
rollout: full
---

## zh-CN
修复从其他应用点击 Raft 消息、回复串或频道链接跳回 App 时停留在首页的问题，保留当前会话页面并重试前台切换瞬间被中断的链接解析请求，让链接直接打开目标会话。

## en
Fix tapping a Raft message, thread, or channel link from another app landing on Home instead of the linked conversation by keeping the running app open on warm return and retrying link lookups interrupted by the foreground transition.

## ja
他のアプリから Raft のメッセージ・スレッド・チャンネルリンクをタップして戻った際にホーム画面に留まってしまう問題を修正し、対象の会話がそのまま開くようにしました。
