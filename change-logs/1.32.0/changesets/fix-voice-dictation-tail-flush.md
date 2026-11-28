---
type: fixed
area: composer
platforms: [ios, android, desktop]
rollout: full
---

## zh-CN
修复了移动端和桌面端语音听写点击停止、松手结束或在麦克风仍开启时直接点击发送容易截断最后几个字的问题：点击停止后在后台保留 300ms 尾音采集窗口（界面仍立即回到空闲态），在录音器停止时完整冲刷不足 100ms 的残留音频帧与 iOS `AVAudioConverter` 尾缓冲后再发送停止帧；麦克风仍开启时直接点击发送也会立即冲刷尾音并在最多 1 秒内等待最终确认文本后再发出。

## en
Fixed voice dictation occasionally clipping the last word when tapping Stop, releasing push-to-talk, or tapping Send directly while the mic is still active on mobile and desktop: stopping keeps microphone capture open for a 300ms background post-roll while returning the UI to idle immediately and flushes any remaining sub-100ms PCM tail frames (plus iOS `AVAudioConverter` end-of-stream frames) before sending the WebSocket `stop` frame; tapping Send while actively listening now immediately flushes tail audio and waits up to 1s for the confirmed final words before dispatching the message.

## ja
モバイルおよびデスクトップの音声ディクテーションで、停止タップ時・長押し解除時・マイク起動中の直接送信時に末尾の単語が途切れる問題を修正しました。停止操作後に UI を即座に待機状態へ戻しつつバックグラウンドで 300ms のポストロール録音を行い、100ms 未満の残余 PCM フレームと iOS の `AVAudioConverter` 終端フレームを送信してから WebSocket の `stop` フレームを送るようにし、マイク起動中に直接送信した場合も即座に末尾音声をフラッシュして最大 1 秒間確定テキストを待ってから送信するようにしています。
