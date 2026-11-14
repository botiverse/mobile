---
type: fixed
area: composer
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

修复了在极少数情况下草稿和待发送消息被误删的问题。以前，如果应用启动时无法读取已保存的登录信息，或者某一次读取临时失败，应用会当作你已退出登录，删除所有账号的草稿和尚未发出的消息。现在只有真正退出登录时才会删除；无法读取时会保留这些内容，重新登录同一账号后即可找回。另外，冷启动时选中服务器后的增量同步不会再被跳过，离线冷启动也会在账号就绪后重新读取上次保存的功能开关。

## en

Fixed drafts and unsent messages that could be deleted in rare cases. Before, if the app could not read the saved sign-in when it started, or one read failed for a moment, it acted as if you had signed out and deleted the drafts of every account and messages not yet sent. Now they are deleted only when you really sign out; when the sign-in cannot be read they are kept, and you get them back by signing in to the same account again. Also, the sync after a server is selected at startup is no longer skipped, and an offline start reads the last saved feature settings again once your account is ready.

## ja

まれに下書きと未送信のメッセージが削除されることがある問題を修正しました。これまでは、起動時に保存されたサインイン情報を読み取れなかった場合や、読み取りが一時的に失敗した場合に、サインアウトしたものとして扱い、すべてのアカウントの下書きとまだ送信されていないメッセージを削除していました。現在は実際にサインアウトしたときだけ削除します。サインイン情報を読み取れないときは保持され、同じアカウントで再度サインインすると元に戻ります。また、起動時にサーバーを選択した後の同期がスキップされなくなり、オフラインで起動した場合もアカウントの準備ができた時点で前回保存された機能設定を読み込み直します。
