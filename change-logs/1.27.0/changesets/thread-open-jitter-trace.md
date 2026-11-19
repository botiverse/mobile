---
type: added
area: diagnostics
platforms:
  - android
  - ios
pull_request: null
rollout: flag
---

## zh-CN

（内部诊断）打开频道、私信或话题时，App 会对一部分打开记录消息列表从出现到稳定的过程：缓存与网络内容何时到达、列表何时定位完成，以及每一次非用户操作造成的内容跳动及其原因。记录写入本机诊断日志，并在服务器未开启隐私模式时上传为诊断追踪；记录比例由服务器的功能开关控制，只观察、不改变滚动。

## en

(Internal diagnostics) For a share of channel, DM and thread opens, the app records how the message list settles: when cached and network content arrive, when the list is in place, and every jump the user did not cause, with its cause. The record goes to the on-device diagnostics log and, unless the server runs in privacy mode, is uploaded as a diagnostic trace. The share is set by a server feature flag; the recording only observes and never moves the list.

## ja

（内部診断）チャンネル・DM・スレッドを開いたときの一部について、メッセージ一覧が落ち着くまでの過程を記録します。キャッシュとネットワークの内容が届いた時刻、一覧の位置が決まった時刻、ユーザー操作によらない表示の跳びとその原因です。記録は端末内の診断ログに残り、サーバーがプライバシーモードでなければ診断トレースとして送信されます。記録する割合はサーバーの機能フラグで決まり、記録は観察のみで一覧を動かしません。
