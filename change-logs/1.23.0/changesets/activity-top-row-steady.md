---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 4461
rollout: full
---

## zh-CN

修复动态列表第一项来回跳动的问题：讨论串有新回复（包括你自己发的）后，该行先移到顶部，随后的刷新又把它放回原来的第二位，再下一次刷新又移回顶部。现在有新回复的讨论串会一直留在顶部，直到服务器返回最新结果。

## en

Fixed the first row of Activity switching back and forth: after a new reply in a thread (including your own), its row moved to the top, the next refresh put it back below the previous first row, and the one after moved it up again. A thread with a new reply now stays on top until the server catches up.

## ja

アクティビティの先頭の行が入れ替わり続ける問題を修正しました。スレッドに新しい返信（自分の返信を含む）があると、その行が先頭に移動した後、次の更新で以前の先頭の下に戻り、さらに次の更新で再び先頭に移動していました。新しい返信のあるスレッドは、サーバーが追いつくまで先頭に表示されたままになりました。
