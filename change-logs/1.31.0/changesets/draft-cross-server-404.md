---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复了保存了其他服务器或已删除频道的草稿时，每次冷启动仍会重复向当前服务器查询无效频道的问题（跨服务器外部频道草稿继续正常共享）。

## en

Fixed repeated background lookups on app launch for drafts saved on another server or in a deleted channel, while keeping external-channel drafts shared across participating servers.

## ja

別サーバーや削除済みチャンネルの下書きが残っている場合に、起動のたびに不要なチャンネル参照を繰り返していた問題を修正しました（外部チャンネルの下書き共有は引き続き維持されます）。
