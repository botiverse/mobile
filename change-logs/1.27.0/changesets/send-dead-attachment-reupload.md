---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: 4920
rollout: full
---

## zh-CN

如果发送消息时服务器拒绝了其中的附件（例如附件已过期或已被取消），未发送的消息下方会说明「有附件已不可用。请重新上传或移除后再发送。」，不再提供无法成功的「重试」，与网页版的提示一致。

## en

When a message can't be sent because the server refused one of its attachments (for example, the upload expired or was canceled), the unsent message now says "An attachment is no longer available. Upload it again or remove it, then send." and no longer offers a Retry that could never succeed, matching the web's message.

## ja

添付ファイルの期限切れやキャンセルなどでサーバーが添付ファイルを受け付けず、メッセージを送信できなかった場合、未送信のメッセージの下に「添付ファイルが利用できなくなりました。もう一度アップロードするか削除してから送信してください。」と表示され、成功しない「再試行」は表示されなくなりました。ウェブ版のメッセージと同じです。
