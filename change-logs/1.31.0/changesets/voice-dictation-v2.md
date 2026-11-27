---
type: added
area: chat
platforms: [android, ios, desktop]
rollout: flag
---

## zh-CN
消息输入框新增流式语音输入（需 Server 开启 `voice_dictation_v1` 且非访客成员）。点击或按住麦克风即可零等待开始实时识别并写入草稿，支持按住说话浮层、音浪反馈（遵循减弱动态设置）、停止后后台自动润色、停止后立即发送时的短暂等待，以及本地化权限、额度与异常提示（含 Trace ID 复制与额度用尽后的禁用状态提示）。

## en
Added streaming voice dictation to the message composer (behind the per-server `voice_dictation_v1` flag for non-guest members). Tap or press-and-hold the microphone for instant dictation with a hold-to-talk pill, audio-reactive halo (respecting reduced motion), non-blocking background refinement on stop with brief final-text wait on immediate send, and localized permission, quota, and error notices with Trace ID copy and disabled-mic reset hints.

## ja
メッセージ入力欄にストリーミング音声入力を追加しました（サーバーの `voice_dictation_v1` フラグが有効なゲスト以外のメンバー向け）。マイクのタップまたは長押しで即座に聞き取りを開始でき、長押し停止ピル、音量連動ハロー（視差効果を減らす設定に対応）、停止後のバックグラウンド整形、停止直後の送信待機、さらに権限・利用上限・エラー通知（Trace ID コピーおよび上限到達時のマイク無効ヒント付き）に対応します。
