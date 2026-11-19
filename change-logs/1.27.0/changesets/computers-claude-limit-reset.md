---
type: changed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 5032
rollout: flag
---

## zh-CN

Claude Code 的用量卡片现在只有一个“使用额度重置”，和 Claude Code 自己的 /limit-reset 一样：点一下会立即读取（“这需要一点时间”），只有一种可用的重置时直接确认，有多种时先选择，确认前随时可以“不，先留着”。旧版每周一次 5 小时重置的账号在上限时，读取本身就会用掉这次重置：卡片会说明“5 小时额度已重置 · 每周上限仍然适用”并重新读取用量，不会再用第二次。没有可用的重置时，卡片会说明没有用掉任何重置。

## en

The Claude Code usage card now has one "Use a limit reset", as Claude Code's own /limit-reset: a tap reads right away ("This takes a moment"), one available reset goes straight to its confirm, several are chosen first, and "No, keep it" cancels before anything is used. On an account with the older once-a-week 5-hour reset at its limit, the read itself uses that reset: the card says "5-hour limit reset · your weekly limit still applies" and reads the usage again, and never uses a second one. With no reset to use, the card says nothing was used.

## ja

Claude Code の使用量カードは「上限リセットを使う」1 つだけになり、Claude Code の /limit-reset と同じように動きます。タップするとすぐに読み取り（「少し時間がかかります」）、使えるリセットが 1 つならそのまま確認へ、複数あれば先に選びます。確認の前なら「いいえ、残しておく」でやめられます。週 1 回の 5 時間リセットだけの旧プログラムのアカウントが上限に達しているときは、読み取りそのものでそのリセットが使われます。カードには「5 時間の上限をリセットしました · 週間の上限はそのまま適用されます」と表示して使用量を読み直し、2 回目は使いません。使えるリセットがないときは、何も使われなかったと表示します。
