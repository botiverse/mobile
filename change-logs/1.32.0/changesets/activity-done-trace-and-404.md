---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
- 动态列表标记已处理时，若对话或对象已删除（HTTP 404）将保持关闭并后台刷新，不再回弹报错。
- 补齐动态列表标记已处理提交（`done_committed`）与撤销/新动态取消（`undo`、`calloff_*`）的端侧观测事件。

## en
- Keep Activity rows closed and refresh in the background instead of rolling back with an error when Mark as Done hits a deleted target (HTTP 404).
- Complete Activity Mark as Done telemetry for primary commits (`done_committed`) as well as Undo and call-off reappearances (`undo`, `calloff_*`).

## ja
- アクティビティで「完了」にした対象が削除済み（HTTP 404）の場合は元に戻してエラーにせず、閉じたままバックグラウンドで更新するように修正しました。
- アクティビティの完了確定（`done_committed`）および取り消し・自動解除（`undo`、`calloff_*`）の計測イベントを補完しました。
