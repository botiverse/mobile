---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4890
rollout: full
---

## zh-CN

迁移 Agent 时，进度说明现在会显示时间：源计算机等待 Agent 完成当前工作时显示最多还需几分钟，开始打包但还没有报告大小时显示已用几分钟，与网页版一致。计算机页面上，长按（或悬停）磁盘或内存用量会显示剩余空间和总容量，“磁盘不足”标记也会显示剩余空间；磁盘大小按十进制单位显示（1 GB = 10 亿字节），与网页版和 macOS 访达一致。

## en

While an agent moves to another computer, the progress line now shows time, as on the web: while the source computer waits for the agent to finish its current work, at most how many more minutes it waits; once it is packing but has not reported sizes yet, how many minutes it has packed so far. On the Computers page, a long press (or hover) on a disk or memory meter shows what is free of the total, and so does the Low disk badge; disk sizes use decimal units (1 GB = 1,000,000,000 bytes), as on the web and in macOS Finder.

## ja

エージェントを別のコンピューターに移動するとき、進行状況の説明に時間が表示されるようになりました（Web 版と同じ）。移動元のコンピューターがエージェントの現在の作業の完了を待っている間は最大であと何分待つかを、パッケージ化を始めてまだサイズが報告されていない間はこれまでの経過時間を表示します。コンピューターページでは、ディスクやメモリの使用量を長押し（またはホバー）すると空き容量と合計容量が表示され、「ディスク不足」バッジでも空き容量を確認できます。ディスクのサイズは Web 版や macOS の Finder と同じく 10 進単位（1 GB = 10 億バイト）で表示します。
