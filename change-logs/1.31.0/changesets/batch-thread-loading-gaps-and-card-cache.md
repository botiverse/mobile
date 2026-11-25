---
type: fixed
area: chat
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

批量修复了会话消息流中的多类空白占位与重复加载闪烁问题：在线刷新尚未完成时不再跨越本地缓存断层，离线跨段加载也不再把零散实时消息逐条拼出连续多个「正在加载更早的消息…」提示；移除了加载状态行的 600ms 透明空白高度与底部 36dp 空闲白条；修复内联图片、音频、视频、引用消息链接、转发合集及任务详情的缓存首帧命中与异常兜底，避免卡在空白或加载中状态。

## en

Fixed multiple timeline gap, blank spacer, and per-message card loading flashes across conversations: gated offline gap-crossing strictly on offline stand-ins and proven segments so online refreshes and loose realtime messages never stack repeated "Loading older messages…" rows; removed the 600ms transparent loading-row height reservation and 36dp idle bottom spacer; and added shared Frame-1 URL/negative caching and terminal error settlement for inline images, audio/video previews, quoted permalinks, forward bundles, and task details.

## ja

会話タイムラインの空白スペースや繰り返される読み込み表示をまとめて修正しました。オンライン更新中にローカルキャッシュの断層を跨がないようにし、オフライン時にも断片的なリアルタイムメッセージ間で「以前のメッセージを読み込み中…」が連続表示されないようにしました。あわせて 600ms の透明な読み込み行と下部の 36dp 空白を削除し、インライン画像・音声・動画・引用メッセージリンク・転送まとめ・タスク詳細の初回フレームキャッシュ参照とエラー確定処理を追加しました。
