---
type: improved
area: notifications
platforms:
  - android
pull_request: 3820
rollout: full
---

## zh-CN

在 Android 13 及以上版本，登录后进入主页时，如果还没有允许通知，Raft 会请求一次通知权限，新安装的用户不再错过通知。已经登录、但从未允许通知的用户，在更新到 1.17.0 后首次打开时也会被询问一次。已经允许或之前询问过就不会再弹出；之后仍可在“设置 › 通知”中开启。

## en

On Android 13 and later, when you reach Home after signing in and notifications aren't allowed yet, Raft asks once for permission to send them, so new installs no longer miss notifications. If you're already signed in and never allowed notifications, Raft asks once the first time you open it after updating to 1.17.0. It doesn't ask if they're already allowed or if it asked before; you can still turn them on in Settings › Notifications.

## ja

Android 13 以降では、ログイン後にホームを開いたときに通知がまだ許可されていなければ、Raft が通知の許可を一度だけ求めます。新しくインストールした場合も通知を見逃しません。すでにログインしていて通知を許可したことがない場合は、1.17.0 に更新して最初に開いたときに一度だけ求められます。すでに許可している場合や、以前に一度求めた場合は表示されません。あとから「設定 › 通知」でオンにできます。
