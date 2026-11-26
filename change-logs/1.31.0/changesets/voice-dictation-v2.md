---
type: added
area: chat
platforms: [android, ios, desktop]
rollout: flag
---

## zh-CN
消息输入框新增流式语音输入（需 Server 开启 `voice_dictation_v1` 且非访客成员）。点击麦克风即可实时识别并写入草稿，支持音浪反馈、结束后的自动润色以及权限/额度/断线等提示与 Trace ID 复制。

## en
Added streaming voice dictation to the message composer (behind the per-server `voice_dictation_v1` flag for non-guest members). Tap the microphone to dictate live into the composer with audio-level feedback, non-blocking final refinement on stop, and localized permission, quota, and error notices with Trace ID copy.

## ja
メッセージ入力欄にストリーミング音声入力を追加しました（サーバーの `voice_dictation_v1` フラグが有効なゲスト以外のメンバー向け）。マイクをタップするとリアルタイムで下書きに文字起こしされ、音量レベル表示、停止時の最終整形、権限・利用上限・エラー通知（Trace ID コピー付き）に対応します。
