---
type: added
area: settings
platforms: [android, ios, desktop]
rollout: flag
---

## zh-CN
新建反馈表单及反馈工单回复输入框支持流式语音输入（需 Server 开启 `voice_dictation_v1` 且非访客成员）。点击或按住麦克风即可实时识别并插入或追加到反馈内容中，离开页面或提交时会自动停止并释放麦克风。

## en
Added streaming voice dictation to the New Feedback form (including `feedback:submit` cards) and feedback ticket reply composer behind the per-server `voice_dictation_v1` flag for non-guest members. Tap or press-and-hold the microphone to dictate or append text cleanly, with automatic stop and microphone cleanup on submit or page exit.

## ja
新規フィードバック入力欄（`feedback:submit` カードを含む）およびフィードバックチケット返信欄にストリーミング音声入力を追加しました（サーバーの `voice_dictation_v1` フラグが有効なゲスト以外のメンバー向け）。マイクのタップまたは長押しで音声をリアルタイムに入力・追記でき、送信時や画面を離れる際に自動で録音停止とマイク解放を行います。
