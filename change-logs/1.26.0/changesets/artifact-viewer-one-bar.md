---
type: fixed
area: artifacts
platforms:
  - android
  - ios
pull_request: 4869
rollout: full
---

## zh-CN

打开 Artifact 时不再短暂出现两层顶栏。以前在页面加载的一瞬间，App 自己的标题栏下面还会露出一行只有“返回”箭头的栏，随后才换成页面自己的顶栏。现在从打开的第一刻起只有一个顶栏：加载期间是 App 的标题栏，页面的顶栏准备好后直接接替它的位置，像素拼图加载动画保持在原处，直到内容加载完成。

## en

Opening an Artifact no longer briefly shows two top bars. Before, for a moment while the page loaded, a second row with only a Back arrow appeared under the app's own title bar before the page's own top bar took over. Now there is one top bar from the moment it opens: the app's title bar while it loads, replaced in place by the page's own bar once it is ready, with the pixel puzzle loading animation staying where it is until the content has loaded.

## ja

Artifact を開いたときに、上部のバーが一瞬 2 段に表示されなくなりました。これまではページの読み込み中、アプリのタイトルバーの下に「戻る」矢印だけの行が一瞬表示され、その後ページ自身の上部バーに切り替わっていました。現在は開いた瞬間から上部のバーは 1 つだけです。読み込み中はアプリのタイトルバー、準備ができるとページ自身のバーが同じ位置で置き換わり、ピクセルパズルの読み込みアニメーションは内容の読み込みが終わるまで同じ位置に表示されます。
