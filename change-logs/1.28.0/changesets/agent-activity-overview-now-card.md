---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

Agent「动态」标签页概览底部的「现在」卡片与网页端一致，改用资料页「最近动态」的同一张卡片：左侧是 Ottie，第一行在工作时显示「工作中 · 已持续 3 分 12 秒」（从这一轮开始计时，逐秒更新），下面一行是正在做的步骤；空闲时显示「空闲 · 上一轮 15:53 结束」；其他状态（需要登录、崩溃、额度用完等）照旧显示状态本身。再下一行简短写出这一轮（或上一轮）回应的是谁、做了什么。原来的「上面共 N 轮 · N 步 · 都很顺利」统计行不再显示。

## en

The "now" card at the bottom of an agent's Activity overview now matches the web and is the same card as the profile's Recent activity: Ottie beside it, and a first line that reads "Working · for 3m 12s" while the agent works (timed from the turn's start, counting each second) with the step it is on below, or "Idle · last turn ended 15:53" when it is idle; any other state (a sign-in, a crash, a used-up limit) still reads as the status itself. The next line says, briefly, who the current (or last) turn answers and what it did. The "N turns above · N steps · nothing went wrong" line is gone.

## ja

エージェントの「アクティビティ」タブの概要の下にある「現在」カードが Web と同じになり、プロフィールの「最近のアクティビティ」と同じカードを使うようになりました。横に Ottie が表示され、1 行目は作業中なら「作業中 · 3 分 12 秒 経過」（ターンの開始から 1 秒ごとに更新）とその下に今の作業内容、待機中なら「待機中 · 前回のターンは 15:53 に終了」と表示されます。それ以外の状態（サインインが必要、クラッシュ、利用上限など）はこれまでどおり状態そのものを表示します。次の行には、今の（または前回の）ターンが誰に応えて何をしたかを短く表示します。「N ターン · N ステップ · 問題なし」の行は表示されなくなりました。
