---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 5306
rollout: full
---

## zh-CN

修复了动态列表左滑操作中的两个问题：松手、系统取消手势、点击顶部标题栏/筛选栏或切换标签页后，列表行不再停留在半滑开偏移位置；在带「取消关注」或「静音」双按钮的行上完成「已完成」操作时，绿色完成区域会先完整铺满整行，再平滑收起行高移除，不再只铺满一半或闪回原位后突然消失。

## en

Fixed two Activity swipe issues: rows no longer get stuck partially offset after lifting, gesture cancellation, tapping the header/filter bar, or switching tabs; and completing Done on two-button rows now expands Done across the full row width before smoothly collapsing the row height out without flashing back.

## ja

アクティビティ一覧のスワイプ操作に関する 2 つの不具合を修正しました。指を離したあとやジェスチャー中断時、ヘッダー・フィルターバーのタップ時、タブ切替時に行がずれたまま残らないようになり、2 ボタン行で「完了」を実行した際も緑の完了エリアが行全体に広がってから高さが滑らかに折りたたまれ、元の位置に一瞬戻ってから消える現象がなくなりました。
