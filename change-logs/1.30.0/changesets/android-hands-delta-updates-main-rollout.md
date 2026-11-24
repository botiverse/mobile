---
type: improved
area: updates
platforms:
  - android
rollout: flag
---

## zh-CN

非 Google Play 渠道的 Android 正式版与预览版应用内更新现已接入 Hands 增量更新流程。在服务端 `delta_updates` 灰度开启后，应用内更新可下载体积更小的增量补丁并自动校验还原安装包；若补丁校验或应用失败则自动回退至完整安装包下载，同时保留下载进度展示与未知来源安装权限引导。

## en

Non-Play Android production (`main`) and preview builds now route in-app updates through the Hands incremental update flow. Once the server-side `delta_updates` rollout is enabled for a device, updates download smaller verified delta patches and automatically fall back to the full APK if patching fails, while preserving download progress display and install-permission guidance.

## ja

Google Play 以外の Android 正式版およびプレビュー版のアプリ内更新を Hands 差分更新フローへ切り替えました。サーバー側の `delta_updates` 段階展開が有効な端末では、検証済みの小さい差分パッチをダウンロードして更新でき、パッチの検証や適用に失敗した場合は自動的にフル APK のダウンロードへフォールバックします。ダウンロード進捗表示と提供元不明アプリのインストール許可案内も引き続き利用できます。
