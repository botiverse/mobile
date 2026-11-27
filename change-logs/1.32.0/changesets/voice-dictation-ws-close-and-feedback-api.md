---
type: fixed
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
修复了语音听写 WebSocket 正常结束或取消时因底层会话提前取消而误发 `1011`（Client failure）关闭帧的问题，现统一以 `1000` 及明确的关闭原因优雅断开连接；同时接入了 Raft 服务端语音听写与智能体语音通话反馈代理接口、隐式编辑指标统计及隐私模式防泄漏保护，为后续反馈界面提供基础数据层支持。

## en
Fixed voice dictation WebSocket connections sending RFC 6455 close code `1011` ("Client failure") on normal completion or cancellation by flushing the close frame cleanly before tearing down the Ktor session and sending explicit close reasons with code `1000`. Also wired the shared data layer for Raft's voice dictation and agent voice call feedback proxy routes, permit `sessionId`/`traceId` correlation, `voice_feedback_sample_v0` feature-flag evaluation, and privacy-mode-gated dictation edit-outcome tracking.

## ja
音声ディクテーションの WebSocket が正常終了またはキャンセル時に Ktor セッションの早期キャンセルによって `1011`（Client failure）を送信していた問題を修正し、クローズフレームをフラッシュしてから明示的な理由付きでコード `1000` による正常クローズを行うようにしました。あわせて、Raft サーバー経由の音声ディクテーションおよびエージェント音声通話フィードバック API、許可証の `sessionId`/`traceId` 連携、`voice_feedback_sample_v0` フラグ評価、プライバシーモード対応の編集指標トラッキングを共有データ層に追加しました。
