---
type: fixed
area: artifacts
platforms: [android, ios]
rollout: general
---

## zh-CN
修复在移动端打开 Artifact 页面以及在 Android 上唤起或收起评论键盘时的白屏闪烁：新 WebView 容器（`compose/web-container`）与现有 Artifact 容器统一采用 Chromium 标准 `WindowInsets` 透传（M139+ 键盘走 `window.visualViewport`，M144+ 系统栏走 `env(safe-area-inset-*)`，旧版内核保留兼容兜底），并持续保持主题画布底色与冷启顶栏叠加布局，避免 WebView 高度跳动与瞬时白闪。

## en
Fixed white flashes when cold-opening Artifacts and when opening or dismissing the comment keyboard on Android: both the new `compose/web-container` and the Artifact viewer now use standard Chromium `WindowInsets` passthrough (M139+ keyboard via `window.visualViewport`, M144+ system bars via `env(safe-area-inset-*)`, with legacy fallback on older WebViews), persist the theme canvas background across paint and theme changes, and overlay the loading header so the WebView never resizes during toolbar handoff.

## ja
モバイルで Artifact を開く際や Android でコメント入力キーボードを開閉する際の白画面フラッシュを修正しました。新しい `compose/web-container` と Artifact ビューアーの双方で Chromium 標準の `WindowInsets` パススルー（M139+ は `window.visualViewport`、M144+ は `env(safe-area-inset-*)`、旧カーネルは互換フォールバック）を採用し、テーマのキャンバス背景色を維持しつつ読み込み中の上部バーを重ね合わせることで、WebView の高さ変動と一瞬の白フラッシュを防ぎます。
