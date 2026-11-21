---
type: changed
area: agents
platforms: [android, ios, desktop]
pull_request: 5201
rollout: full
---

## zh-CN
修改运行中 Agent 的 Runtime/模型配置或通过 Computer 页面批量更换模型时，不再弹出手动「立即重启」提示或勾选框；变更保存后会直接自动生效（旧版 Computer 在忙碌时会在当前轮结束后自动切换），个人资料页也移除了过时的等待切换提示行。

## en
Saving runtime/model edits on a running agent or bulk-changing models from the Computer page no longer prompts for a manual restart or shows a "Restart running agents now" checkbox; changes apply automatically (or switch automatically after the current turn on older Computers), and the stale pending-switch hint under Model was removed.

## ja
実行中のエージェントのランタイム／モデル設定変更および Computer ページでのモデル一括変更で、手動の再起動ボタンや「今すぐ再起動」チェックボックスを廃止しました。変更は自動で即時適用され（古い Computer で実行中の場合はこのターン終了後に自動で切り替わります）、モデル行の古い待機ヒントも削除しています。
