---
type: fixed
area: privacy
platforms:
  - android
  - ios
pull_request: 5082
rollout: full
---

## zh-CN

（隐私）连接到开启隐私模式（`RAFT_PRIVACY_MODE`）的服务器时，“设置 › 反馈”不再提供“新建反馈”，并说明原因；在服务器告知是否开启隐私模式之前同样不提供。服务器未配置反馈时，App 不再改为直接通过 Hands 提交。Android 上，崩溃、ANR、系统终止和 WebView 崩溃报告仍保存在本机，但只有在已登录的所有服务器都确认未开启隐私模式时才会上传，否则留到下次启动再判断；自动检查更新也遵循同样规则，手动“检查更新”不受影响。

## en

(Privacy) On a server that runs in privacy mode (`RAFT_PRIVACY_MODE`), Settings › Feedback no longer offers New Feedback and says why; it is also not offered until the server says whether privacy mode is on. When a server isn't set up to take feedback, the app no longer files it straight to Hands instead. On Android, crash, ANR, system-kill and WebView-crash reports are still kept on the device, but are uploaded only when every server you're signed in to has said privacy mode is off; otherwise they wait for the next launch. Automatic update checks follow the same rule; your own Check for update still works.

## ja

（プライバシー）プライバシーモード（`RAFT_PRIVACY_MODE`）で動くサーバーでは、「設定 › フィードバック」に「新しいフィードバック」が表示されなくなり、その理由が表示されます。サーバーがプライバシーモードの有無を返すまでも表示されません。サーバーでフィードバックが設定されていない場合に、App が代わりに Hands へ直接送信することもなくなりました。Android では、クラッシュ、ANR、システムによる終了、WebView のクラッシュのレポートは引き続き端末に保存されますが、サインインしているすべてのサーバーがプライバシーモードはオフだと返した場合にのみアップロードされ、それ以外は次回起動時に改めて判断します。自動のアップデート確認も同じルールに従います。手動の「アップデートを確認」はこれまでどおり使えます。
