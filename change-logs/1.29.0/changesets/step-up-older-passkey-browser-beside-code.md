---
type: fixed
area: settings
platforms:
  - ios
pull_request: 5418
rollout: flag
---

## zh-CN

账户同时有旧版通行密钥和验证器时，在 iPhone 和 iPad 的“确认是你本人”里点“使用通行密钥”，不会再只剩下 6 位验证码：验证码输入框下面多了“在浏览器中用通行密钥验证”，点它会打开和只有旧版通行密钥时相同的网页验证，确认后回到 Raft，继续刚才的操作。也可以照常输入验证码。

## en

If your account has an older passkey and an authenticator app, tapping "Use Passkey" in "Verify it's you" on iPhone and iPad no longer leaves you with only the 6-digit code. Under the code field, "Verify with your passkey in the browser" opens the same web check used when an older passkey is all you have; confirm there and Raft carries on with what you were doing. You can still enter the code instead.

## ja

以前のパスキーと認証アプリの両方がある場合、iPhone と iPad の「本人確認」で「パスキーを使用」をタップしても、6 桁のコードしか選べない状態にはならなくなりました。コード入力欄の下の「ブラウザでパスキーを使って確認する」から、以前のパスキーしかない場合と同じ Web での確認が開きます。そこで確認すると Raft に戻り、行っていた操作がそのまま続きます。これまでどおりコードを入力することもできます。
