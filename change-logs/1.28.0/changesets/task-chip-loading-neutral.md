---
type: fixed
area: messages
platforms:
  - android
  - ios
pull_request: 5145
rollout: full
---

## zh-CN

打开会话时，消息里的任务引用（例如 `#proj:#18`）不再先显示成深色标签、再跳成状态色：加载期间先显示同样大小的中性样式，状态到了只换颜色，不会闪也不会变大小。

## en

When you open a conversation, task references in messages (like `#proj:#18`) no longer show as a dark tag first and then jump to their status color. While loading they keep the same size in a neutral look, and only the color changes when the status arrives.

## ja

会話を開いたとき、メッセージ内のタスク参照（`#proj:#18` など）が濃い色のタグから状態の色へ切り替わることがなくなりました。読み込み中は同じ大きさの落ち着いた見た目で表示され、状態が届くと色だけが変わります。
