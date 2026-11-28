---
type: fixed
area: composer
platforms: [ios, android, desktop]
rollout: full
---

## zh-CN
修复了移动端和桌面端语音听写点击停止或松手结束时最后几个字容易被截断的问题：点击停止后在后台保留 300ms 尾音采集窗口（界面仍立即回到空闲态），并在录音器停止时完整冲刷不足 100ms 的残留音频帧与 iOS `AVAudioConverter` 尾缓冲后再发送停止帧。

## en
Fixed voice dictation occasionally clipping the last word when tapping Stop or releasing push-to-talk on mobile and desktop: stopping now keeps microphone capture open for a 300ms background post-roll while returning the UI to idle immediately, and flushes any remaining sub-100ms PCM tail frames (plus iOS `AVAudioConverter` end-of-stream frames) before sending the WebSocket `stop` frame.

## ja
モバイルおよびデスクトップの音声ディクテーションで停止タップ時や長押し解除時に末尾の単語が途切れる問題を修正しました。停止操作後に UI を即座に待機状態へ戻しつつバックグラウンドで 300ms のポストロール録音を行い、100ms 未満の残余 PCM フレームと iOS の `AVAudioConverter` 終端フレームを確実に送信してから WebSocket の `stop` フレームを送るようにしています。
