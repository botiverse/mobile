---
type: improved
area: updates
platforms: [android]
rollout: full
---

## zh-CN
升级 Android 应用内更新 SDK 至 `0.16.2` 并接入 `HandsUpdateOffer.downloadSizeBytes`：支持按已安装 APK 哈希精准匹配跨包体增量补丁，更新页直接展示实际需要下载的增量补丁大小（无法应用补丁时自动回退展示完整安装包大小）。

## en
Upgraded the Android in-app updater SDK to `0.16.2` (and Hands CLI release tooling to `0.5.89`) and wired `HandsUpdateOffer.downloadSizeBytes` into the update screen so Android sends the installed APK SHA-256 for cross-ABI delta matching and displays the actual delta patch download size (falling back to the full APK size when no matching patch applies).

## ja
Android のアプリ内アップデート SDK を `0.16.2`（および Hands CLI を `0.5.89`）へ更新し、更新画面に `HandsUpdateOffer.downloadSizeBytes` を接続しました。インストール済み APK の SHA-256 に基づくクロス ABI 差分パッチの正確なマッチングと、実際の差分パッチサイズ表示（適用不可時はフル APK サイズへフォールバック）に対応します。
