---
type: added
area: composer
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
为移动端输入框语音听写 v2 接入每会话延迟、停顿间隔与就绪前音频缓冲遥测（`slock.composer.voice_dictation_session`），与 Web 端保持指标对齐：记录点击/长按模式、结束原因、麦克风启动/建连/首字/收尾耗时、流路由来源（`direct` / `raft_edge`）及就绪前入队与发送分片计数；仅包含闭集枚举与非负整数，不包含任何听写文本、草稿、联系人提示或会话 ID，并自动遵循 `RAFT_PRIVACY_MODE` 隐私开关。

## en
Added per-session client trace telemetry (`slock.composer.voice_dictation_session`) for mobile composer voice dictation v2 with parity to Web: records tap/hold mode, stop reason (`button`/`esc`/`blur`/`error`/`server`/`submit`/`reset`), mic/connect/first-partial/finishing latencies, mid-sentence and inter-final pause gaps, stream route source (`direct`/`raft_edge`), and pre-ready PCM chunk queued/sent counts. Strictly closed-set enums and non-negative whole numbers only—zero transcript text, drafts, contact hints, or IDs—emitted via `RaftClientTraceEvents` respecting `RAFT_PRIVACY_MODE`.

## ja
モバイル入力欄の音声ディクテーション v2 向けに、Web と同等のセッション単位テレメトリ（`slock.composer.voice_dictation_session`）を追加しました。タップ／長押しモード、終了理由、マイク起動・接続・初回部分結果・終了処理の各レイテンシ、文内および確定間のポーズ間隔、ストリーム経路（`direct` / `raft_edge`）、準備完了前の音声チャンクのキュー数／送信数を記録します。閉集合の列挙値と非負整数のみを送信し、文字起こし本文・下書き・連絡先ヒント・ID は一切含まず、`RAFT_PRIVACY_MODE` に自動準拠します。
