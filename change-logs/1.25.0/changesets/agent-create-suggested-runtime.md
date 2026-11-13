---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4723
rollout: full
---

## zh-CN

Agent 发来的「创建 Agent」卡片可以建议一个运行时。从卡片打开创建对话框时，如果所选计算机可以使用这个运行时，会预先选中它，并显示「由 <Agent> 建议」，你仍然可以换成其他运行时。如果这台计算机不能使用它，就不会预先选中任何运行时，对话框会说明原因，请你自己选择。这个建议不会改变下次创建时默认使用的运行时，除非你用它创建了 Agent。

## en

A Create Agent card from an agent can now suggest a runtime. When you open the create dialog from the card, the runtime is preselected if the chosen computer can use it, with "Suggested by <agent>" under it, and you can still pick another one. If that computer can't use it, nothing is preselected and the dialog says why, so you choose. The suggestion doesn't change the runtime your next create starts on unless you create the agent with it.

## ja

エージェントからの「エージェントを作成」カードで、ランタイムを推奨できるようになりました。カードから作成ダイアログを開くと、選択中のコンピューターで使える場合はそのランタイムが選ばれた状態になり、「<エージェント> が推奨」と表示されます。ほかのランタイムに変えることもできます。そのコンピューターで使えない場合は何も選ばれず、理由が表示されるので、ご自身で選んでください。推奨されたランタイムでエージェントを作成しない限り、次回の作成で最初に選ばれるランタイムは変わりません。
