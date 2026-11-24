---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

优化实时状态更新与会话打开时的网络请求与界面刷新：合并高频设备状态事件下的智能体与设备列表刷新，去重并短时缓存重复的频道详情请求，并批量提交会话打开时的链接卡片更新以减少界面抖动。

## en

Reduced redundant network traffic and layout shifts when opening conversations or receiving frequent machine status events by throttling repeated `machine:status`-driven agent/machine refreshes, coalescing duplicate channel detail requests with mutation-safe invalidation, and micro-batching concurrent link-card updates.

## ja

頻繁なマシン状態イベント受信時および会話を開いた際のネットワーク負荷とレイアウトシフトを軽減しました。高頻度な状態更新によるエージェント／マシン一覧の再取得を抑制し、重複するチャンネル詳細リクエストを統合するとともに、同時取得したリンクカードの反映をまとめて画面のがたつきを抑えました。
