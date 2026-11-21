---
type: improved
area: settings
platforms:
  - android
  - ios
  - desktop
pull_request: 5282
rollout: full
---

## zh-CN

在“设置 › 账户”中，没有密码的账户（只用 Google、GitHub 等已连接账户登录）现在和网页一样显示“设置密码”和“通过邮件设置密码”按钮，不再显示修改密码的表单。点按钮会发送一封带设置链接的邮件，并提示已发送；发送失败时会说明原因并附上追踪 ID。断开账户唯一的登录账户时，会先提示“请先设置密码”，改为发送这封邮件，而不是报错。

## en

In Settings › Account, an account without a password (one that signs in only with a connected account such as Google or GitHub) now shows "Set a password" with a "Set password by email" button instead of the change-password form, as on the web. The button sends an email with a link to set a password and says when it was sent; if sending fails, it says why, with the trace ID. Disconnecting the account's only connected sign-in now asks "Set a password first" and sends that email instead of stopping with an error.

## ja

「設定 › アカウント」で、パスワードのないアカウント（Google や GitHub などの連携アカウントだけでログインしている場合）には、Web と同じく、パスワード変更フォームの代わりに「パスワードを設定」と「メールでパスワードを設定」ボタンを表示するようにしました。ボタンを押すと、パスワードを設定するリンクを記載したメールを送信し、送信したことを表示します。送信に失敗した場合は、理由とトレース ID を表示します。唯一のログイン用アカウントの連携を解除しようとすると、エラーで止まる代わりに「先にパスワードを設定してください」と表示し、同じメールを送信します。
