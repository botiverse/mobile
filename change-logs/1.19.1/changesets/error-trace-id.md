---
type: improved
area: errors
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4189
rollout: full
---

## zh-CN

服务器请求失败时，错误提示会和网页版一样附上追踪 ID（如“追踪 ID 4bf92f35…”）：提示条上点“复制”即可复制完整 ID，发给我们后能在服务器上找到这次请求。已覆盖无法打开消息列、私信、任务等打开失败的提示，以及设置中语言、外观、通知页面的加载错误。网络断开、请求没有收到回应时，提示与以前相同。

## en

When a server request fails, the error now shows its trace ID as on the web (for example "Trace ID 4bf92f35…"): tap Copy on the toast to copy the full ID, and send it to us so we can find that request on the server. This covers the "couldn't open" errors for threads, direct messages and tasks, and the loading errors on the Language, Appearance and Notifications settings pages. When there is no connection and the request gets no answer, the error looks as before.

## ja

サーバーへのリクエストが失敗したとき、エラーに Web 版と同じくトレース ID（例:「トレース ID 4bf92f35…」）が表示されるようになりました。トーストの「コピー」をタップすると ID 全体をコピーでき、送っていただければサーバー上でそのリクエストを特定できます。スレッド、ダイレクトメッセージ、タスクを開けなかったときのエラーと、設定の言語・外観・通知ページの読み込みエラーが対象です。接続がなくリクエストに応答がないときは、これまでどおりの表示です。
