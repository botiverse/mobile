---
type: added
area: web-container
platforms:
  - desktop
pull_request: 5698
rollout: flag
---

## zh-CN

在桌面端（macOS）新增原生 `WKWebView` 引擎（通过轻量 JNI/JAWT 桥接，挂在 `mobile_web_container_v1` 开关下，默认关闭）：支持原生 `allowScripts = false`、按 `WKWebsiteDataStore` 作用域隔离（含冷重启后登出清理）以及零反射/零轮询的异步导航拦截与标准 JSB 通信；当原生桥接不可用时自动平滑回退到现有 Wry 引擎。

## en

Added a direct native macOS `WKWebView` engine for Desktop via a lightweight JNI/JAWT bridge (gated behind `mobile_web_container_v1`, default off): supports native `allowScripts = false`, `WKWebsiteDataStore` isolation scopes (including cold-restart logout cleanup), and reflection-free, polling-free asynchronous navigation gating and standard JSB messaging, with automatic fallback to Wry when unavailable.

## ja

デスクトップ版（macOS）向けに軽量 JNI/JAWT ブリッジによるネイティブ `WKWebView` エンジンを追加しました（`mobile_web_container_v1` フラグ配下、デフォルト無効）。ネイティブの `allowScripts = false`、`WKWebsiteDataStore` によるスコープ分離（再起動後ログアウト時のクリーンアップ含む）、リフレクション・ポーリング不要の非同期ナビゲーション制御と標準 JSB 通信に対応し、利用不可時は自動的に Wry へフォールバックします。
