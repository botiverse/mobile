---
type: added
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

计算机详情页的 Agent 列表支持批量操作，和网页版一致：有管理计算机权限时，标题旁多了「选择」按钮，进入选择模式后每行出现勾选框，可「全选」「清空选择」或「取消」（返回键也会退出选择模式）。选中后可批量「启动」离线的 Agent、「停止」在线的 Agent（先确认）、「重启 / 重置」（可选重启、重置会话并重启、完整重置并重启），或「更换模型」：选好运行时、模型和推理强度，确认每个 Agent 会怎么变（有自定义设置或无变化的会跳过），可勾选立即重启运行中的 Agent，完成后列表上方显示结果，未完成的可以重试。

## en

The agents on a Computer's page can now be managed together, as on the web. People who can manage computers get a **Select** button: rows get checkboxes, with **Select all**, **Clear selection** and **Cancel** (Back also leaves select mode). The selected agents can be started (the offline ones), stopped (the online ones, after a confirm), restarted or reset (Restart, Reset session & restart, or Full reset & restart), or moved to another model with **Change model**: pick the runtime, model and reasoning, review what happens to each agent (ones with custom settings or no change are skipped), optionally restart running agents now, and a banner above the list says how it went, with **Try again** for any that didn't finish.

## ja

コンピューター画面のエージェントを、ウェブ版と同じようにまとめて操作できるようになりました。コンピューターを管理できる場合は「選択」ボタンが表示され、各行にチェックボックスが付きます。「すべて選択」「選択を解除」「キャンセル」が使え、戻る操作でも選択モードを終了します。選択したエージェントは、オフラインのものを「起動」、オンラインのものを（確認のうえ）「停止」、「再起動 / リセット」（再起動、セッションをリセットして再起動、完全リセットして再起動）、または「モデルを変更」できます。モデルの変更では、ランタイム・モデル・推論を選び、各エージェントがどう変わるかを確認し（カスタム設定がある、または変更がないものはスキップ）、実行中のエージェントを今すぐ再起動することもできます。結果は一覧の上に表示され、完了しなかったものは再試行できます。
