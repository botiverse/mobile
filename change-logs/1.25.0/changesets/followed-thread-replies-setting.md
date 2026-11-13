---
type: added
area: settings
platforms:
  - android
  - ios
  - desktop
pull_request: 4667
rollout: full
---

## zh-CN

「设置 › 通知」的「内容」里新增「我关注的消息列中的回复」开关，排在「声音」之后，和网页版一致，对所有设备生效。关闭后，你关注的消息列或任务里的回复只有 @你 时才通知；消息列仍会出现在动态里，未读也照常更新。服务器还不支持这个设置时，不显示这一行。通知设置保存失败时，提示下方会显示这次请求的追踪 ID，可以复制。

## en

Settings › Notifications › Content now has a "Replies in threads I follow" switch after Sound, as on the web, and it applies to all your devices. When it's off, a reply in a thread or task you follow notifies you only when it @mentions you; the thread stays in Activity and its unread count still updates. The row doesn't appear when the server doesn't support the setting yet. If saving a notification setting fails, the warning now shows that request's trace ID, which you can copy.

## ja

「設定 › 通知」の「内容」に、「サウンド」の次に「フォロー中のスレッドへの返信」スイッチを追加しました。Web 版と同じく、すべてのデバイスに適用されます。オフにすると、フォロー中のスレッドやタスクへの返信は、あなたへの @メンションがあるときだけ通知されます。スレッドはアクティビティに残り、未読数もこれまでどおり更新されます。サーバーがまだこの設定に対応していない場合、この行は表示されません。通知設定の保存に失敗したときは、警告の下にそのリクエストのトレース ID が表示され、コピーできます。
