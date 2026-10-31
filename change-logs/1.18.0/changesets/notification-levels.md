---
type: added
area: notifications
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4021
rollout: full
---

## zh-CN

通知设置与网页版统一：
- 每个频道可以单独设置通知：所有新消息、仅@我和已关注的消息列，或不通知；选中的就是当前生效的那一档（没单独设置时跟随服务器）。可在侧边栏长按菜单的「通知：…」和频道设置里调整。消息列只看关注与否，私信只有静音。
- 设置里新增「所有设备」：暂停通知（30 分钟、1 小时、2 小时或到明天 9:00）、安静时段和提示音，对这个账号登录的所有设备生效；通知预览的隐私选项也改为整个账号通用。原来的设备开关改名为「在此设备接收通知」。
- 在其他设备上已读的消息，手机上的通知会自动收起，应用角标与实际未读数一致。
- Android 上也会收到其他工作区的通知，点开会切换到对应工作区。
- 服务器还不支持新通知设置时，应用保持原来的行为。

## en

Notification settings now match the web:
- Each channel can have its own level: All new messages, Mentions & followed threads, or Nothing. The selected one is the level in effect (it follows the server until you change it). Change it from "Notifications: …" in the sidebar long-press menu or channel settings. A thread notifies when you follow it; a DM is only muted or not.
- Settings has a new "Every device" section: Pause notifications (30 minutes, 1 hour, 2 hours or until 9:00 tomorrow), Quiet hours and sound, applied on every device signed in to this account. The notification preview privacy choice is also account-wide now. The device switch is renamed "Receive notifications on this device".
- Notifications for messages you've read on another device are cleared from your phone, and the app badge matches your unread count.
- On Android, notifications from your other workspaces now show too, and tapping one switches to that workspace.
- On servers that don't support the new settings yet, the app works as before.

## ja

通知設定が Web 版と統一されました。
- チャンネルごとに通知レベルを設定できます：すべての新着メッセージ、@メンション・フォロー中のスレッドのみ、通知しない。選択中の項目が実際に適用されているレベルです（変更するまではサーバーの設定に従います）。サイドバーの長押しメニューの「通知：…」とチャンネル設定から変更できます。スレッドはフォローしていれば通知され、DM はミュートのみです。
- 設定に「すべてのデバイス」を追加しました。通知を一時停止（30 分、1 時間、2 時間、明日 9:00 まで）、おやすみ時間、通知音を、このアカウントでログインしているすべてのデバイスに適用します。通知プレビューのプライバシー設定もアカウント全体で共通になりました。端末のスイッチは「このデバイスで通知を受け取る」に名前が変わりました。
- 別のデバイスで既読にしたメッセージの通知はスマートフォンから自動的に消え、アプリのバッジは未読数と一致します。
- Android では、ほかのワークスペースの通知も表示され、タップするとそのワークスペースに切り替わります。
- 新しい通知設定にまだ対応していないサーバーでは、これまでどおり動作します。
