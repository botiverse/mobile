---
type: changed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 5511
rollout: full
---

## zh-CN

添加计算机的弹窗现在与网页保持一致：移除「云计算机（即将推出）」占位选项；未达上限且有添加权限时，直接打开连接命令界面，并在命令上方提示「笔记本、台式机或云服务器（VPS）都可以：在它上面运行下面这条命令。」；达到上限或无权限时停留在单列选项界面，不再出现两列高度不等的卡片。

## en

Add Computer now matches the web dialog: removed the disabled "Cloud computer (coming soon)" card, opens straight on the connect command when under the plan limit with permission to add (with the "A laptop, a desktop or a cloud server (VPS): run this command on it." hint above the command), and uses a single-column choice list on the first step when at the limit or without permission.

## ja

コンピューターの追加シートが Web と同じ動きになりました。「クラウドコンピューター（近日公開）」の無効カードを削除し、追加権限があり上限未満のときは接続コマンドの画面を直接開きます（コマンドの上に「ノート PC、デスクトップ、クラウドサーバー（VPS）など：そこで次のコマンドを実行してください。」を表示）。上限に達しているときや権限がないときは、単一列の選択リストで最初のステップにとどまります。
