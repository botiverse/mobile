---
type: fixed
area: activity
platforms:
  - android
  - ios
pull_request: 5179
rollout: full
---

## zh-CN

在 Activity 中对消息行执行「标记为已完成」或「取消关注」后，顶部统计（进行中 / 未读）和底部标签未读角标现在会立即扣减，不再等待 8 秒撤销提示结束；点击「撤销」或收到新消息时会立刻恢复。

## en

Activity header counts ("in progress · unread") and the Activity badge now drop immediately after Mark as Done or Unfollow instead of waiting for the 8-second Undo toast to expire, and restore immediately on Undo or newer activity.

## ja

Activity で行を「完了にする」または「フォロー解除」した際、8 秒の取り消しトーストが終わるのを待たずにヘッダー件数とバッジが即座に更新されるようになりました。「元に戻す」や新しいアクティビティの受信時には即座に復元されます。
