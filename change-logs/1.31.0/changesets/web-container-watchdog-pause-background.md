---
type: fixed
area: browser
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复了在 Android 与 iOS 上使用内置浏览器或网页容器跳转到外部登录（OAuth）、切换应用或锁屏进入后台时，网页加载看门狗仍在后台计时并误报超时或白屏错误的问题；切回前台后将从暂停处继续计时。

## en

Fixed false page-load timeout and blank-screen errors on Android and iOS when an in-app browser or web container moves to the background during OAuth handoff, app switching, or screen lock; the load watchdog now pauses while hidden and resumes its remaining budget upon returning to the foreground.

## ja

Android および iOS でアプリ内ブラウザや Web コンテナが OAuth 認証への遷移、アプリ切り替え、画面ロックなどでバックグラウンドに移行した際、読み込みウォッチドッグがバックグラウンドで計測を続けてタイムアウトや白画面エラーを誤検出していた問題を修正しました。非表示中は一時停止し、フォアグラウンド復帰時に残り時間から再開します。
