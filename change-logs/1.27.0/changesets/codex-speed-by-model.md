---
type: changed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

创建和编辑 Codex 智能体时，速度选项现在按所选模型显示：只有一档加速的模型显示「Fast mode」开关；有多档加速的模型（例如 GPT-6.2 Astra）改为显示「Speed」菜单，可选「Standard」（默认）、「Fast」或「Ultrafast」；不支持加速的模型两者都不显示。切换模型时，不再适用的速度设置不会被一起保存。需要服务器支持，未更新的服务器上显示与之前相同。

## en

When you create or edit a Codex agent, its speed choice now follows the model you pick: a model with one faster speed shows the Fast mode switch, a model with several (such as GPT-6.2 Astra) shows a Speed menu with Standard (the default), Fast and Ultrafast instead, and a model with no faster speed shows neither. Switching models no longer saves a speed setting that does not apply to the new model. This needs the server's support; until the server is updated, the form looks as before.

## ja

Codex エージェントの作成・編集で、速度の設定が選んだモデルに合わせて表示されるようになりました。高速モードが 1 段階のモデルでは「Fast mode」スイッチを、複数段階あるモデル（GPT-6.2 Astra など）では代わりに「Speed」メニュー（「Standard」（既定）、「Fast」、「Ultrafast」）を表示し、高速化に対応しないモデルではどちらも表示しません。モデルを切り替えたとき、新しいモデルに当てはまらない速度設定が一緒に保存されることはなくなりました。サーバー側の対応が必要で、サーバーが更新されるまでは従来どおりの表示です。
