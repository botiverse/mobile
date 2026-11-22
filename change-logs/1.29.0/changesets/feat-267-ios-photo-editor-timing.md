---
type: changed
area: diagnostics
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5417
---

## zh-CN

（内部诊断）为图片选择与编辑器打开流程补充耗时打点，拆分记录 iOS 相册导出与缓存、预览页生命周期以及编辑画布解码就绪与首次手势响应耗时；不影响用户可见行为。

## en

(Internal diagnostics) Added timing points across the photo picker and image editor flow to record iOS photo picker export/cache durations, preview controller lifecycle milestones, and editor canvas decode/ready/first-gesture timings; no user-facing behavior change.

## ja

（内部診断）写真選択から画像エディタ起動までの診断計測ポイントを追加し、iOS の写真ピッカー書き出し・キャッシュ所要時間、プレビュー画面のライフサイクル、編集キャンバスのデコード完了・操作可能・初回ジェスチャ応答の経過時間を記録するようにしました。ユーザー向けの挙動に変更はありません。
