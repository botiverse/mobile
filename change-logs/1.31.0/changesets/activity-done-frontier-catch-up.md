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

修复在 Activity 列表刚收到实时新消息后立刻点击「完成」时，条目短暂消失又闪回的问题；列表现在会在同步追平后补齐完成边界，不再误把已看过的内容重新弹回。

## en

Fixed an issue where marking an Activity item Done right after a live message arrived could make the row briefly disappear and flash back; the list now catches up the Done frontier silently when no newer activity arrived.

## ja

Activity 一覧に新着メッセージがリアルタイム反映された直後に「完了」を押した際、項目が一瞬消えて再表示される問題を修正しました。新しいアクティビティがない場合は完了フロンティアを自動で追従します。
