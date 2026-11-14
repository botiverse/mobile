---
type: improved
area: computers
platforms:
  - android
  - ios
pull_request: 4562
rollout: full
---

## zh-CN

「计算机」卡片的 ⋯ 菜单新增「删除计算机」，和网页版一样会先确认（计算机上还有 Agent 时提示先移除），删除失败时显示原因和追踪 ID。卡片的下一步也补齐了：不能从 App 升级的计算机会显示带终端图标的升级按钮，点开即可复制在那台机器上运行的安装命令；之前的安装没有完成或上次从网页升级失败时，升级按钮旁会出现 ⚠，说明原因和处理步骤；由 Raft Desktop 管理的计算机会注明「Raft Desktop 会自动更新这台计算机。」从首页的计算机提醒点「查看」时，只涉及一台计算机会直接打开它，涉及多台会打开只显示「需要处理」的列表。

## en

A Computers card's ⋯ menu has Delete computer, with the web's confirm (a computer that still has agents says to remove them first); a failed delete says why and shows its trace ID. Cards also show the rest of the web's next steps: a computer the app can't upgrade gets an Upgrade button with the terminal mark that opens the install command to run on it; a ⚠ beside Upgrade explains when an earlier installation needs a repair or the last web upgrade failed, with the steps; and Raft Desktop's own computer says that Raft Desktop keeps it up to date. View on a Home computer notice opens that computer when it names one, or the list filtered to Needs attention when it names several.

## ja

「コンピューター」のカードの ⋯ メニューに「コンピューターを削除」が追加されました。Web 版と同じく確認があり（エージェントが残っているコンピューターは先に外すよう案内します）、削除に失敗したときは理由とトレース ID が表示されます。カードの次の操作も Web 版にそろいました。アプリからアップグレードできないコンピューターにはターミナルアイコン付きのアップグレードボタンが表示され、そのマシンで実行するインストールコマンドを開けます。以前のインストールが完了していないときや前回の Web からのアップグレードが失敗したときは、アップグレードの横の ⚠ で理由と手順を確認できます。Raft Desktop が管理するコンピューターには「このコンピューターは Raft Desktop が自動で最新の状態に保ちます。」と表示されます。ホームのコンピューターの通知で「表示」をタップすると、対象が 1 台ならそのコンピューターを、複数なら「要対応」で絞り込んだ一覧を開きます。
