---
type: improved
area: share
platforms: [android, ios]
rollout: full
---

## zh-CN
- 对齐系统分享（Share to Raft）与消息转发的目标列表：新增「讨论串与任务」最近会话分组，搜索结果保留回复串和任务会话，并支持直接打开对应回复串或任务会话的输入框预填分享内容。
- 优化 Android 从系统分享打开 Raft 的速度：复用已存活的应用窗口避免清空任务栈重建、直接复用当前工作区已缓存的频道与私信数据、并行拉取目标列表并移除分享文件拷贝时的冗余磁盘同步阻塞。

## en
- Align the system Share-to-Raft target list with Message Forward: show the recent "Threads & tasks" group, include thread and task conversations in search results, and hand off directly into the selected reply thread or task conversation composer with shared content staged.
- Speed up opening Raft from the Android system share sheet by reusing the live app shell instead of tearing down and recreating the task, reusing warm Home channels/DMs and member caches, loading targets in parallel with envelope staging, and removing redundant per-file `fsync` stalls during intake.

## ja
- システム共有（Share to Raft）の送信先一覧をメッセージ転送と揃え、「スレッドとタスク」の最近の会話グループを表示し、検索結果にもスレッド・タスク会話を含め、選択したスレッドまたはタスク会話の入力欄へ共有内容を直接引き継げるようにしました。
- Android のシステム共有から Raft を開く際の速度を改善し、起動中シェルの再利用、キャッシュ済みのチャンネル・DM・メンバー情報の活用、送信先読み込みの並列化、ファイルコピー時の冗長なディスク同期の削減を行いました。
