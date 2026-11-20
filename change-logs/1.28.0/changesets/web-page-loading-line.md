---
type: improved
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: 5172
rollout: full
---

## zh-CN

App 内网页（应用内浏览器、设置里的网页）加载时，顶部的进度条改为贴着上边缘的一条细线，两端平直，不再是圆角的胶囊形，也不再来回滑动。页面还没有进度时，细线停在左侧一小段不动；有进度后跟着页面向前走，不会倒退；加载完成时走到头再淡出，加载失败时立即消失。很快就加载完的页面不再闪出这条线。打开系统的“减少动态效果”后，细线不做动画。

## en

While a web page loads in the app (the in-app browser and the settings web pages), the progress bar is now a thin line flush with the top edge, with square ends, instead of a rounded pill, and it no longer sweeps back and forth. Before the page reports progress, the line rests as a short still segment; it then follows the page forward and never moves back. When the page finishes, the line runs to the end and fades out; when the page fails, it disappears at once. A page that loads very quickly no longer flashes the line. With the system's reduced motion setting on, the line does not animate.

## ja

アプリ内の Web ページ（アプリ内ブラウザ、設定の Web ページ）の読み込み中に表示される進捗バーが、上端に沿った細い線になりました。両端は角のない直線で、角丸のピル形ではなくなり、左右に往復する動きもなくなりました。ページの進捗がまだないときは、短い線が動かずに表示されます。進捗が届くとページに合わせて前に進み、後ろには戻りません。読み込みが終わると線は端まで進んでからフェードアウトし、失敗したときはすぐに消えます。すぐに読み込みが終わるページでは、線は表示されません。システムの「視差効果を減らす」などの動きを減らす設定がオンのときは、線はアニメーションしません。
