---
type: improved
area: navigation
platforms:
  - ios
pull_request: 5385
rollout: full
---

## zh-CN

优化 iOS 原生导航栏进入会话、成员、频道详情、动态与任务页时的首帧显示：在页面推入动画开始前就预填标题、头像/频道图标和右上角按钮，不再出现空顶栏滑入后再闪现按钮的现象。

## en

Pre-populates the iOS native navigation bar title, leading avatar or channel tile, and right-hand action buttons before the push transition begins for conversations, members, channel details, Activity, and Tasks, eliminating the empty-bar flash during navigation.

## ja

iOS ネイティブナビゲーションバーで会話・メンバー・チャンネル詳細・アクティビティ・タスク画面へ遷移する際、プッシュアニメーション開始前にタイトル・アイコン・右上ボタンを先行表示し、空のバーから遅れて表示されるちらつきを解消しました。
