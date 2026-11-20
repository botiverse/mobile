---
type: improved
area: settings
platforms:
  - ios
pull_request: 5038
rollout: flag
---

## zh-CN

如果你的账户只有旧版通行密钥，在 iPhone 和 iPad 的“验证是你本人”里点“使用通行密钥”，会直接在应用内打开一个安全的网页。用这把通行密钥确认后，会自动回到 Raft 并继续刚才的操作，不用再去网页版重做。网页没确认就关掉时，可以点“在浏览器中继续”再试一次。验证框里还会提示：在网页版新加一把通行密钥，以后就能直接在 App 里验证。服务器尚未开启此功能时，仍显示“在网页打开”。

## en

If your account only has an older passkey, tapping "Use Passkey" in "Verify it's you" on iPhone and iPad opens a secure web page inside the app. Confirm with that passkey and Raft comes back and picks up the action you were doing, with no need to redo it on the web. If you close the page without confirming, "Continue in browser" lets you try again. The dialog also points out that adding a new passkey on the web lets you confirm right in the app next time. Until the server turns this on, you still get "Open on the web".

## ja

アカウントに以前のパスキーしかない場合、iPhone と iPad の「本人確認」で「パスキーを使用」をタップすると、アプリ内で安全な Web ページが直接開きます。そのパスキーで確認すると Raft に戻り、行っていた操作がそのまま続きます。Web 版でやり直す必要はありません。確認せずにページを閉じた場合は、「ブラウザで続ける」でもう一度試せます。ダイアログには、Web 版で新しいパスキーを追加すれば次回からアプリ内で確認できることも表示されます。サーバー側で有効になるまでは、これまでどおり「Web 版で開く」が表示されます。
