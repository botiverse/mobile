---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

从消息输入框草稿中移除已上传完成的附件时，现在会调用服务端释放预留空间并立即刷新上传额度；移除过程中展示「正在移除附件…」并阻止发送，若网络或服务端出错则保留该附件以便点击移除重试。

## en

Removing a completed attachment from a message draft now releases its server reservation immediately and refreshes upload quota. While removal is in progress, the tile shows "Removing attachment…" and blocks sending; if removal fails, the attachment stays visible so you can choose Remove to try again.

## ja

メッセージ下書きからアップロード済みの添付ファイルを削除する際、サーバー上の予約を即座に解放してアップロード上限を更新するようになりました。削除中は「添付ファイルを削除しています…」を表示して送信をブロックし、失敗した場合は「削除」から再試行できるよう添付ファイルを保持します。
