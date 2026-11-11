---
type: added
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

Agent 的「动态」页新增「子 Agent」视图：Agent 启动过子 Agent 后，切换栏里会出现「子 Agent」及其数量，列出运行中和已结束的子 Agent（任务、类型、步数、用时和状态）。点按一个子 Agent 可查看它自己的动态；有权限停止当前回合的人，还能单独停止一个正在运行的子 Agent，其他子 Agent 和 Agent 本身继续运行。原始日志中启动子 Agent 的那一行和子 Agent 的「Subagent」标签都可以直接打开它。工具调用的名称也与网页版一致（如「Running command」「Reading file」），各运行时读起来相同。

## en

An agent's Activity tab has a new Subagents view: once the agent has started a subagent, the view switch shows Subagents with a count, listing the running and ended ones (what each was asked, its type, steps, time and state). Tap one to read its own activity; people who can stop the agent's turn can also stop a single running subagent while the agent and its other subagents keep going. In the raw log, the row that started a subagent and a subagent's badge open it directly. Tool calls are named as on the web ("Running command", "Reading file"), the same on every runtime.

## ja

エージェントの「アクティビティ」タブに「サブエージェント」ビューを追加しました。エージェントがサブエージェントを起動すると、切り替えに「サブエージェント」と件数が表示され、実行中と終了済みのサブエージェント（依頼内容、種類、ステップ数、時間、状態）が一覧になります。タップするとそのサブエージェント自身のアクティビティを確認できます。ターンを停止できる人は、実行中のサブエージェントを個別に停止でき、エージェント本体と他のサブエージェントは動き続けます。生ログでは、サブエージェントを起動した行とサブエージェントのバッジから直接開けます。ツール呼び出しの名前も Web 版と同じ表記（「Running command」「Reading file」など）になり、どのランタイムでも同じように表示されます。
