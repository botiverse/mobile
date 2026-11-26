---
type: fixed
area: browser
platforms:
  - ios
rollout: full
---

## zh-CN

修复了在 iOS 内置浏览器或 Raft 应用页面里，网页申请麦克风时应用直接闪退的问题。现在网页的麦克风和摄像头申请一律直接拒绝，不再弹出系统授权框（与桌面端内置浏览器一致），页面其余功能照常可用。

## en

Fixed a crash on iOS when a web page in the in-app browser or a Raft App asked for the microphone. Web pages' microphone and camera requests are now always declined without a system prompt, as in Raft Desktop's built-in browser, and the rest of the page keeps working.

## ja

iOS のアプリ内ブラウザや Raft アプリで Web ページがマイクを要求するとアプリがクラッシュする不具合を修正しました。Web ページからのマイク・カメラの要求は、デスクトップ版の内蔵ブラウザと同様にシステムの許可ダイアログを出さずに常に拒否し、ページのほかの機能はそのまま使えます。
