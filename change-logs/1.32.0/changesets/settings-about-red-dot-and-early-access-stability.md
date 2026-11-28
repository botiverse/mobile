---
type: fixed
area: settings
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复了点击「设置 › 关于」后，「检查更新」和「抢先体验」会在等待网络返回期间隐藏 1–3 秒，以及抢先体验通道在打开页面时短暂回退到主通道并触发多余下载的问题；现在「关于」页面会立即展示更新入口与通道状态，并在有可用更新时在「检查更新」行显示与外层一致的红点提示。

## en

Fixed Settings › About hiding Check for update and Early access for 1–3 seconds while waiting for the update check and briefly resetting the Early access channel to main (triggering a spurious download) before the session loaded. Check for update and Early access now appear immediately, and Check for update shows a matching red dot whenever an update is available or ready to install.

## ja

「設定 › このアプリについて」を開いた際に、更新確認の完了待ちで「アップデートを確認」と「早期アクセス」が 1〜3 秒間非表示になる問題と、セッション読み込み前に早期アクセスチャネルが一時的にメインチャネルへ切り替わり不要なダウンロードが発生する問題を修正しました。今後は両項目が即座に表示され、利用可能なアップデートがある場合は「アップデートを確認」行にも一致する赤いドットが表示されます。
