---
type: changed
area: composer
platforms: [ios, android, desktop]
rollout: full
---

## zh-CN
升级了移动端与桌面端语音听写 v2 协议及交互体验：按下麦克风立即并行采集音频与建连，待长按/短按手势在 350ms 内判定后在 v2 `start` 首帧发送 `mode: "hold" | "tap"`（零丢开头语音、零额外建连往返）；停麦后界面立即回到空闲态，同时在后台精修落稳前保留待定虚线下划线，并在定稿、结束、手动编辑或发送时自动清除；停麦后精修期间点击发送改为所见即所发立即送出，麦克风仍开启时直接点击发送则继续立即冲刷尾音并在最多 1 秒内等待尾字后再发。

## en
Updated mobile and desktop voice dictation v2 startup, composing underline, and submit behavior: pressing the mic immediately begins local audio buffering, permit fetch, and WebSocket connect in parallel, holding only the v2 `start` frame until the 350ms hold-vs-tap gesture settles to send `mode: "hold" | "tap"` with zero clipped opening audio and zero extra connect RTT; stopping returns the mic button to idle immediately while keeping the dotted composing underline on unfinalized text until background refinement settles, finishes, or the user edits/submits; tapping Send after stopping now sends the visible text immediately (WYSIWYG) and aborts background refinement, while tapping Send with the mic still live continues to flush trailing audio immediately and wait up to 1s for trailing words.

## ja
モバイルおよびデスクトップの音声ディクテーション v2 の起動・下線表示・送信挙動を更新しました。マイク押下時に音声バッファリング・パーミット取得・WebSocket 接続を並列開始し、350ms の長押し／タップ判定が確定してから v2 `start` フレームに `mode: "hold" | "tap"` を送信することで冒頭音声の欠落や追加 RTT を防ぎます。停止時はマイクボタンを即座に待機状態へ戻しつつ、バックグラウンド精修が確定・完了するか手動編集・送信されるまで未確定テキストの点線下線を維持します。停止後の精修中に送信をタップした場合は表示中のテキストを即座に送信（WYSIWYG）して精修を中断し、マイク起動中の直接送信は従来どおり即座に末尾音声をフラッシュして最大 1 秒待機してから送信します。
