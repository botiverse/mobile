---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 4549
rollout: full
---

## zh-CN

修复动态列表顺序错乱的问题：有新回复的讨论串在服务器更新后可能一直停留在顶部，压在之后有更新动态的行之上，较早的行（如「25 分钟」「1 小时」）显示在「刚刚」的行上方。现在动态始终按最新活动时间排列，最新的在最上面。

## en

Fixed Activity rows showing out of order: a thread with a new reply could stay on top after the server caught up, above rows with newer activity, so older rows (such as "25m" or "1h") showed above "now" rows. Activity is now always ordered by latest activity, newest first.

## ja

アクティビティの行の順序が乱れる問題を修正しました。新しい返信があったスレッドが、サーバーの更新後も先頭に残り、その後に新しいアクティビティがあった行より上に表示されることがあり、古い行（「25分」「1時間」など）が「今」の行より上に並んでいました。アクティビティは常に最新のアクティビティ順（新しいものが先頭）に並ぶようになりました。
