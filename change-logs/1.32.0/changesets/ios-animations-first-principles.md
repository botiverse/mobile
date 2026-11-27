---
type: fixed
area: composer
platforms: [ios]
rollout: full
---

## zh-CN
重构了 iOS 键盘与页面转场同步机制：iOS 键盘高度改为单一真值源驱动，取消与完成状态严格分离并复用常驻探针视图，消除打断键盘动画时的高度跳变、首帧滞后及外接键盘场景下的整页重组；同时修复页面转场排队重入卡死问题，并将导航栏测量、Toast 浮窗隐藏、分享弹窗与账号提示改为跟随系统转场与渲染帧完成，移除硬编码延迟。

## en
Refactored iOS keyboard and transition synchronization to remove hardcoded delays and dual-source handoffs: iOS keyboard tracking now drives a single authoritative height source with strict cancel-vs-complete separation and a reused persistent probe view (preventing interruption jumps, frame-1 stalls, and full-subtree recomposition with hardware keyboards). Also fixed a navigation transition completion re-entry wedge in `RaftIosPageOpenGate` and replaced hardcoded wall-clock timers in nav-bar measurement, toast overlay hiding, share sheet retry, OAuth notices, and popover/player animations with coordinator- and display-link-driven transitions.

## ja
iOS のキーボードおよび画面遷移の同期処理を刷新し、固定タイマーや二重ソース切り替えを撤廃しました。キーボード高さは単一の真値ソースで駆動し、中断と完了を明確に分離しつつ常駐プローブビューを再利用することで、割り込み時の跳ねや初回フレームの遅延、ハードウェアキーボード時の不要な全体再構成を防ぎます。あわせて画面遷移キューの再入デッドロックを修正し、ナビバー計測・Toast オーバーレイ非表示・共有シート・OAuth 通知・ポップオーバー／動画コントロールのアニメーションをトランジション協調・ディスプレイリンク駆動へ統一しました。
