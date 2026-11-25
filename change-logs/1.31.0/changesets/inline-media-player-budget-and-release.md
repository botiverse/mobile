---
type: fixed
area: chat
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复在包含大量内联视频或音频消息的会话中浏览和切页时可能出现内存暴涨、卡顿或崩溃的问题：现在切离页面、滑出屏幕或应用退到后台时会自动释放闲置的聊天音视频播放器，同时限制后台同时保持活跃的播放器数量和预缓冲大小，重新滑回或点播时可无缝恢复播放。

## en

Fixed memory spikes, jank, and out-of-memory crashes when browsing threads with many inline video or audio attachments: inactive or off-screen chat media players are now released automatically when switching pages, scrolling away, or backgrounding the app, with tighter per-player buffering and active player limits that seamlessly resume playback on tap.

## ja

動画や音声の添付が多いスレッドの閲覧・画面切り替え時に発生しうるメモリ急増やクラッシュを修正しました。別画面への移動、画面外へのスクロール、バックグラウンド移行時に待機中のチャット内プレーヤーを自動解放し、同時アクティブ数とバッファ量を抑えつつタップ時にすぐ再生を再開できるようにしました。
