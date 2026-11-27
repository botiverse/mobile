---
type: fixed
area: browser
platforms: [android]
rollout: all
---

## zh-CN
修复了 Android 端内置网页容器中点击新窗口链接（如 Stamp 底部带 `target="_blank" rel="noopener noreferrer"` 的文档、隐私政策、服务状态等链接）偶尔无响应的问题：当 Chromium / 华为 WebView 在弹出传输视图上跳过 `shouldOverrideUrlLoading` 直接触发 `shouldInterceptRequest` 时，现在会在拦截网络请求的同时捕获主框架目标地址，并以 `HitTestResult` 链接作为兜底超时回退，确保链接稳定打开或正确路由到外链子页/系统浏览器。

## en
Fixed an issue on Android where tapping `target="_blank" rel="noopener noreferrer"` links inside the in-app web container (such as Stamp footer links for docs, privacy, terms, and status) could silently do nothing on Chromium / OEM WebViews (including Huawei WebView 114): `WebContainerAndroidNewWindowTransport` now captures main-frame URLs from `shouldInterceptRequest` while still blocking network I/O, uses a `MutableContextWrapper` detached on teardown, and falls back to the anchor `HitTestResult` URL if the transport times out without callbacks.

## ja
Android のアプリ内 Web コンテナで、`target="_blank" rel="noopener noreferrer"` を持つリンク（Stamp フッターのドキュメント、プライバシー、利用規約、ステータスなど）をタップしても無反応になる場合がある問題を修正しました。Chromium や Huawei WebView 114 などでポップアップ転送ビューが `shouldOverrideUrlLoading` を経由せず `shouldInterceptRequest` に直行する場合でも、通信を遮断したままメインフレームの遷移先 URL を取得し、コールバックが届かない場合も `HitTestResult` のリンクへフォールバックして確実に遷移・ルーティングされるようにしました。
