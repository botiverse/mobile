---
type: improved
area: chat
platforms:
  - android
  - ios
  - desktop
pull_request: 5479
rollout: flag
---

## zh-CN

频道公告的更新方式与网页版一致：不再每分钟检查一次，而是在打开频道、有人设置、替换或移除公告、你在应用内看过公告、以及实时连接恢复时更新；回到应用或服务器有新动态时，距离上次读取超过 5 分钟才会再次检查。公告照常保持最新，后台请求大幅减少。

## en

Channel announcements now update the way they do on the web: instead of a check every minute, they update when you open the channel, when someone sets, replaces or removes the announcement, after you view it in the app, and when the realtime connection comes back. Returning to the app, or new activity on the server, checks again only if it was last read more than 5 minutes ago. Announcements stay current with far fewer requests.

## ja

チャンネルのお知らせの更新方法を Web 版に合わせました。1 分ごとの確認をやめ、チャンネルを開いたとき、誰かがお知らせを設定・置き換え・削除したとき、アプリ内でお知らせを表示したあと、リアルタイム接続が戻ったときに更新します。アプリに戻ったときやサーバーで新しいアクティビティがあったときは、前回の読み込みから 5 分以上経っている場合にだけ再確認します。お知らせは引き続き最新に保たれ、リクエストは大幅に減ります。
