---
type: changed
area: artifacts
platforms:
  - android
  - ios
pull_request: 4829
rollout: full
---

## zh-CN

打开 Artifact 时只显示一种加载动画：与网页版相同的像素拼图（按 Artifact 类型拼出网页、文档或图片）。以前 App 会先显示一个小的“加载中”转圈，随后网页再显示像素拼图，看起来像两个不同的加载动画。现在 App 从一开始就显示同一个像素拼图，直到页面内容加载完成。还不知道类型时先拼出文档图标，知道类型后在下一轮开始时才换成对应图标，不会在一轮中途切换。

## en

Opening an Artifact now shows a single loading animation: the same pixel puzzle as on the web, which draws a web page, document or picture by the Artifact's type. Before, the app first showed a small "Loading" spinner and the page then showed its pixel puzzle, so there were two different loaders in a row. Now the app shows the same pixel puzzle from the start until the page has loaded. While the type is not yet known it draws a document, and it changes to the right icon at the start of the next round, never halfway through one.

## ja

Artifact を開くときの読み込みアニメーションが 1 種類になりました。Web 版と同じピクセルパズル（Artifact の種類に応じて Web ページ、ドキュメント、画像の形を描きます）です。これまではアプリが小さな「読み込み中」のスピナーを表示し、その後ページがピクセルパズルを表示していたため、異なる読み込み表示が 2 つ続いていました。現在はアプリが最初から同じピクセルパズルを、ページの読み込みが終わるまで表示します。種類がまだ分からない間はドキュメントの形を描き、種類が分かると次の周回の始めに対応する形へ切り替えます。周回の途中では切り替えません。
