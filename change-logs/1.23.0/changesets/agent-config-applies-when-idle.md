---
type: changed
area: members
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

修改正在运行的智能体的运行时配置（运行时、模型、推理强度等）后，不再立即重启它。保存后会提示「{名称} 的设置已保存」，并说明何时生效：下次空闲时生效；切换运行时或更换 Codex 模型时，会在下次空闲时开始新会话。提示中的「立即重启」可以马上应用更改，只有能重启这个智能体的人才会看到。在更改生效前，「模型」一栏会显示「正在运行 X · 空闲时切换到 Y」。已停止的智能体切换运行时，仍会先请你确认。

## en

Changing a running agent's runtime config (runtime, model, reasoning and so on) no longer restarts it right away. After you save, a notice says "{name}'s settings saved" and when they apply: when it's next idle, or, for a runtime switch or a Codex model change, with a new session when it's next idle. "Restart now" in the notice applies the change at once; only people who can restart the agent see it. Until the change applies, the Model row shows "Running X · switches to Y when idle". Switching a stopped agent's runtime still asks you to confirm first.

## ja

実行中のエージェントのランタイム設定（ランタイム、モデル、推論レベルなど）を変更しても、すぐには再起動しなくなりました。保存すると「{名前} の設定を保存しました」と表示され、いつ適用されるかがわかります。次に待機状態になったときに適用され、ランタイムの切り替えや Codex のモデル変更の場合は、次に待機状態になったときに新しいセッションを開始します。通知の「今すぐ再起動」を押すとすぐに適用できます（このエージェントを再起動できる人にだけ表示されます）。適用されるまでは、「モデル」の欄に「X を実行中 · 待機状態になると Y に切り替わります」と表示されます。停止中のエージェントのランタイムを切り替えるときは、これまでどおり確認が表示されます。
