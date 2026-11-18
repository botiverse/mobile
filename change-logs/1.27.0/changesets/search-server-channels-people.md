---
type: changed
area: search
platforms:
  - android
  - ios
  - desktop
pull_request: 4982
rollout: full
---

## zh-CN

搜索里的频道和成员现在由服务器查找，和网页一致：输入时先显示已加载的结果，服务器的结果一到就替换它们，包括所有你能看到的频道（含已归档和未加入的）和成员。结果按「频道」「人」「计算机」分组，每组标题旁显示总数，结果多时可点「显示全部」并继续「加载更多」。每行最多一个标记（已归档、外部、Agent 或访客）。未加入的公开频道显示「未加入」和频道简介，点开即可预览并加入。离线时只显示已加载的频道和成员并注明；搜索失败时可以重试。来自已归档会话的 Artifact 也会标出「已归档」。

## en

Search now finds channels and people on the server, as on the web: what is already loaded shows while you type, then the server's results replace it, covering every channel you can read (archived and not-joined ones included) and every person you can see. Results come in Channels, People and Computers groups with their count in the heading; a long group has Show all, then Load more. Each row has one mark at most (Archived, External, Agent or Guest). A public channel you haven't joined says "Not joined" with its description and opens on its preview, where you can join. Offline, search says it shows only what is already loaded; if the search fails you can retry. An Artifact from an archived conversation is marked Archived.

## ja

検索のチャンネルとメンバーはサーバーで探すようになりました（Web と同じ）。入力中はまず読み込み済みの結果が表示され、サーバーの結果が届くと置き換わります。閲覧できるすべてのチャンネル（アーカイブ済み・未参加を含む）とメンバーが対象です。結果は「チャンネル」「人」「コンピューター」に分かれ、見出しに件数が表示されます。多い場合は「すべて表示」から「さらに読み込む」で続きを読み込めます。各行のマークは最大 1 つです（アーカイブ済み、外部、エージェント、ゲスト）。未参加の公開チャンネルには「未参加」と説明が表示され、開くとプレビューから参加できます。オフラインのときは読み込み済みの結果だけを表示している旨を示し、検索に失敗したときは再試行できます。アーカイブ済みの会話にある Artifact には「アーカイブ済み」と表示されます。
