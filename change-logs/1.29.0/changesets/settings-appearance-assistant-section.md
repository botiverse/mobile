---
type: added
area: settings
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

<!-- Feature-flag gated behind `personal_assistant`; do not advertise in user-facing 1.29.0 release notes unless `personal_assistant` is at 100%. -->

## zh-CN

在「设置 › 外观」底部补齐与网页版一致的「助理」区块（受 `personal_assistant` 灰度开关控制，仅在非访客且已创建助理时显示）：支持同步账号级的「在所有页面显示」开关，点击「更多助理设置」直接打开助理资料页，并在有记录时展示「由你的助理更改」日志。

## en

Added the web-parity Assistant section to Settings › Appearance (gated behind `personal_assistant` and shown only to non-guest members who have an assistant): includes the account-synced "Show on every page" switch, a "More settings" link to the assistant's profile, and the "Changed by your assistant" action log when entries exist.

## ja

「設定 › 外観」の下部に Web 版と同等の「アシスタント」セクションを追加しました（`personal_assistant` フラグ配下で、ゲスト以外のユーザーがアシスタントを作成済みの場合のみ表示）。アカウント同期の「すべてのページに表示」スイッチ、アシスタントのプロフィールへ遷移する「その他の設定」リンク、および履歴がある場合の「アシスタントによる変更」一覧を備えています。
