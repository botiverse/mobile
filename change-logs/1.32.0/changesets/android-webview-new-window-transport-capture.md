---
type: fixed
area: browser
platforms: [android]
rollout: all
---

## zh-CN
修复了 Android 端内置浏览器中点击带 `target="_blank" rel="noopener noreferrer"` 的外链（如 Stamp 页脚的文档、隐私政策、服务状态等）在华为等系统 WebView 上无反应的问题：关闭内置浏览器不必要的多窗口模式（与 iOS、Artifact 与设置页保持一致），让 `target="_blank"` 与用户触发的 `window.open` 直接走主 WebView 的统一跳转与外链路由链路，无需创建临时隐藏 WebView。

## en
Fixed an issue on Android where tapping `target="_blank" rel="noopener noreferrer"` links in the in-app browser (such as Stamp footer links for docs, privacy, terms, and status) could silently do nothing on OEM WebViews like Huawei WebView 114: disabled unnecessary multi-window mode in the in-app browser (matching iOS, Artifact Viewer, and Settings) so `target="_blank"` and user-gesture `window.open` flow directly through primary `shouldOverrideUrlLoading` without spawning a hidden popup WebView.

## ja
Android のアプリ内ブラウザで `target="_blank" rel="noopener noreferrer"` を持つ外部リンク（Stamp フッターのドキュメント、プライバシー、利用規約、ステータスなど）をタップしても Huawei WebView 114 等で無反応になる問題を修正しました。アプリ内ブラウザで不要なマルチウィンドウ設定を無効化（iOS・Artifact・設定画面と統一）し、一時的な非表示 WebView を作らず主 WebView の通常の遷移・ルーティング経路で直接処理するようにしました。
