---
type: fixed
area: feedback
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5738
---

## zh-CN

修复在「设置 › 反馈」列表点进反馈详情后，手势右滑返回直接退回设置页而非返回反馈列表的问题：反馈详情现作为独立入栈页面推在反馈列表之上，手势右滑、预测性返回与左上角返回均可自然滑回反馈列表，并即时同步已读、回复与关闭状态且保留原列表滚动位置。

## en

Fixed edge swipe-back from a feedback ticket detail under Settings › Feedback popping all the way back to Settings instead of returning to the feedback ticket list: ticket detail now pushes as an independent stack page over the feedback list so iOS interactive swipe-back, Android predictive back, and the top-left Back button all return smoothly to the feedback list with preserved scroll position and live read/reply/close updates.

## ja

「設定 › フィードバック」一覧からフィードバック詳細を開いた後、スワイプバックで一覧ではなく設定画面まで戻ってしまう問題を修正しました。詳細画面を独立したスタックページとして一覧の上に積むことで、iOS のインタラクティブスワイプバック・Android の予測型バック・左上の戻るボタンのすべてが一覧へ自然に戻り、スクロール位置と既読／返信／クローズ状態も保たれます。
