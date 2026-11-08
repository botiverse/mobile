---
type: added
area: agents
platforms:
  - android
  - ios
pull_request: null
rollout: full
---

## zh-CN

使用 Built-in Pi 自定义网关模型的 Agent，如果 Computer 回报当前会话至少有一项模型上限是由 Raft 用默认值补上的，Agent 的“资料”页和运行时设置表单里会显示一条警告，提醒你到服务商那边确认实际上限。警告不显示具体数值或来源，也不会阻止 Agent 运行；在运行时表单里改动设置后，警告会先隐藏。没有收到这类回报时什么都不显示。

## en

When an agent on a Built-in Pi custom gateway model has a Computer report that the current session filled at least one model limit with a Raft default, the agent's Profile and its runtime settings form now show a warning to check your provider's actual limits. The warning shows no values or sources and doesn't stop the agent from running; it hides while you have unsaved changes in the runtime form. Nothing is shown when no such report exists.

## ja

Built-in Pi のカスタムゲートウェイモデルを使うエージェントで、現在のセッションのモデル上限のうち少なくとも 1 つを Raft がデフォルト値で補ったと Computer が報告した場合、エージェントのプロフィールとランタイム設定フォームに、プロバイダーの実際の上限を確認するよう促す警告が表示されるようになりました。警告には数値や取得元は表示されず、エージェントの実行も止めません。ランタイム設定フォームに未保存の変更がある間は非表示になります。そのような報告がない場合は何も表示されません。
