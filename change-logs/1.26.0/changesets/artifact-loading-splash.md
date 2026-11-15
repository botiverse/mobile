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

打开 Artifact 时只显示一种加载动画：与网页版相同的像素拼图（按 Artifact 类型拼出网页、文档或图片）。以前 App 会先显示一个小的“加载中”转圈，随后网页再显示像素拼图，看起来像两个不同的加载动画。现在 App 从一开始就显示同一个像素拼图，并在网页自己的加载画面出现时无缝交接。

## en

Opening an Artifact now shows a single loading animation: the same pixel puzzle as on the web, which draws a web page, document or picture by the Artifact's type. Before, the app first showed a small "Loading" spinner and the page then showed its pixel puzzle, so there were two different loaders in a row. Now the app shows the same pixel puzzle from the start and hands over to the page's own without a visible switch.

## ja

Artifact を開くときの読み込みアニメーションが 1 種類になりました。Web 版と同じピクセルパズル（Artifact の種類に応じて Web ページ、ドキュメント、画像の形を描きます）です。これまではアプリが小さな「読み込み中」のスピナーを表示し、その後ページがピクセルパズルを表示していたため、異なる読み込み表示が 2 つ続いていました。現在はアプリが最初から同じピクセルパズルを表示し、ページ自身の読み込み画面へ切り替わりが見えないように引き継ぎます。
