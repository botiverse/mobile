---
type: fixed
area: chat
platforms:
  - android
  - ios
  - desktop
pull_request: 5369
rollout: full
---

## zh-CN

修复打开任务会话或未加入的频道时，错误向服务端请求该会话「折叠长消息」设置导致返回 400 / 404 的问题：任务会话和回复讨论串现在统一继承父频道的折叠设置，未加入的频道和无权限/失败页面不再发起无效请求，频道设置页也仅对已加入频道显示该开关。

## en

Fixed opening task conversations or unjoined channels issuing unsupported `message-display-settings` requests (400/404): task conversations and reply threads now inherit their parent channel's collapse setting, unjoined/denied/errored routes fail closed without invalid requests, and Channel Settings only shows the toggle for joined channels.

## ja

タスク会話や未参加チャンネルを開いた際に、非対応の「長いメッセージを折りたたむ」設定リクエスト（400/404）を送信してしまう不具合を修正しました。タスク会話やスレッドは親チャンネルの設定を引き継ぎ、未参加・権限なし・エラー画面では不要なリクエストを行わず、チャンネル設定のスイッチも参加済みチャンネルのみ表示します。
