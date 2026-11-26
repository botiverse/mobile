---
type: added
area: settings
platforms:
  - ios
rollout: full
---

## zh-CN

iOS TestFlight 测试版现支持应用内检查更新：启动及在「设置 › 关于」手动检查时会对比 Hands 发布的最新外测构建号（支持同版本号新构建），发现新版后直接引导至 TestFlight 升级；App Store 正式版继续走 App Store 检查逻辑，Xcode 本地开发包保持静默。

## en

iOS TestFlight builds now support in-app update checks against Hands' latest external TestFlight build metadata (including newer build numbers within the same release version) and open TestFlight directly to update, while App Store installs keep App Store lookup and Xcode/simulator builds stay silent.

## ja

iOS の TestFlight ビルドでもアプリ内アップデート確認に対応しました。Hands の最新外部テストビルド番号（同一バージョンの新しいビルドを含む）と比較し、更新があれば TestFlight へ直接案内します。App Store 版は従来の App Store 照会を維持し、Xcode 開発ビルドでは通知を表示しません。
