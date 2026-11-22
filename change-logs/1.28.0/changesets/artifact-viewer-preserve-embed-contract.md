---
type: fixed
area: artifacts
platforms:
  - android
  - ios
rollout: full
pull_request: 5238
---

## zh-CN

修复了在应用内查看 Artifact 时，从「反向链接」或页面内文档链接点开另一篇 Artifact 后，嵌入查看模式失效导致出现双顶栏、开启桌面模式后布局错位、点「原消息」直接关掉查看页并波及后续打开的 Artifact 的问题。同时修复了安卓上从菜单切换桌面模式时状态同步慢一拍的问题。

## en

Fixed an issue where opening another Artifact from a link inside the in-app Artifact viewer (such as Linked from › Documents) could drop the embedded viewer contract, causing stacked native and web headers, desktop-mode layout glitches on phones, and "Original message" closing the viewer across subsequent Artifact opens. Also ensured Android updates the desktop-mode state in the page address before reloading on toggle.

## ja

アプリ内の Artifact ビューアで「リンク元」などのドキュメントリンクから別の Artifact を開いた際、埋め込み表示が外れてヘッダーが二重表示になったり、スマートフォンでのデスクトップモード表示が崩れたり、「元のメッセージ」をタップするとビューアが閉じて以降の表示にも影響する問題を修正しました。あわせて、Android でメニューからデスクトップモードを切り替える際の状態同期のずれも解消しました。
