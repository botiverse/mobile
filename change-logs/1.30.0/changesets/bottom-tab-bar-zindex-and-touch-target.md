---
type: fixed
area: navigation
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复首页底部标签栏在部分层级下可能被覆盖的问题（为外层 `AnimatedVisibility` 补齐 `RaftAppBottomTabBarHostZIndex`），并将手机底部标签栏的垂直点击热区扩展至 60dp 导航栏上下内边距（保持 45dp 视觉高度与底部系统手势安全区拦截不变），同时补充底部栏未命中点击的诊断日志。

## en

Enforced `RaftAppBottomTabBarHostZIndex` on the phone bottom tab bar's `AnimatedVisibility` wrapper in `RaftAppShellSurface`, expanded each phone tab's vertical tap area across the 60dp bar's top and bottom padding while preserving the 45dp visual item height and keeping the system gesture safe area guarded, and added diagnostic logging for unhandled taps swallowed by the bottom bar band.

## ja

ホーム画面下部タブバーの `AnimatedVisibility` コンテナに `RaftAppBottomTabBarHostZIndex` を明示適用し、45dp の見た目サイズとシステムジェスチャー領域の保護を保ったまま 60dp バー上下の余白までタップ領域を拡張しました。あわせて下部バー帯で吸収された未処理タップの診断ログを追加しました。
