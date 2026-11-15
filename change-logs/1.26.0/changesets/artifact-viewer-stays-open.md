---
type: fixed
area: artifacts
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

长时间打开的 Artifact 不会再自己关闭。以前 Artifact 打开约 15 分钟后，页面的登录状态会过期，Artifact 随之关闭。现在 App 会在登录状态过期前自动续期；如果页面仍然掉线，Artifact 会重新登录并重新加载一次，而不是直接关闭。设置中的网页（如账单、电脑、管理）也会这样续期。在 Android 上，系统回收网页进程后，Artifact 会重新打开正在看的那一页。

## en

An Artifact left open for a long time no longer closes by itself. Before, after about 15 minutes the page's sign-in expired and the Artifact closed. Now the app renews the sign-in before it expires, and if the page still gets signed out, the Artifact signs in again and reloads once instead of closing. Web pages in Settings (such as Billing, Computers and Administration) are renewed the same way. On Android, when the system reclaims the page's web process, the Artifact reopens the page you were viewing.

## ja

長時間開いたままの Artifact が勝手に閉じなくなりました。これまでは開いてから約 15 分でページのサインイン状態が切れ、Artifact が閉じていました。現在はサインイン状態が切れる前にアプリが自動で更新し、それでもページがサインアウトした場合は閉じずに再サインインして 1 回再読み込みします。設定内の Web ページ（請求、コンピューター、管理など）も同じように更新されます。Android では、システムが Web ページのプロセスを回収した場合、表示していたページを開き直します。
