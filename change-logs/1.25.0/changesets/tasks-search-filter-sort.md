---
type: changed
area: tasks
platforms:
  - android
  - ios
  - desktop
pull_request: 4646
rollout: full
---

## zh-CN

任务页和网页版一样，由服务器来搜索、筛选和排序。工具栏最前面是「我的」和「全部」（带任务数），然后是占满中间的搜索框、「频道」「状态」「创建人」「负责人」筛选、「清除筛选」、排序（最近更新、最久没更新、最近创建）和列表 / 看板切换。「状态」可以同时勾选多个。「我的」在列表里分成「分配给你」和「你发起的」两组，各自显示数量；「全部」是一整列。页面标题旁显示任务数，筛选后显示「筛选结果 / 总数」。搜索在停止输入后才查询，清空后立即恢复。上次选择的「我的 / 全部」和排序方式会被记住。任务有变化时列表会实时更新；加载失败时保留已显示的任务，并显示可以复制的 Trace ID。

## en

The Tasks page now searches, filters and sorts on the server, as the web does. The toolbar starts with Mine and All with their counts, then the search filling the middle, the Channel, Status, Creator and Assignee filters, Clear all, the sort (Recently updated, Least recently updated, Recently created) and the List / Board switch. Status takes several statuses at once. In the list, Mine has two sections, Assigned and Started by you, each with its count; All is one list. The page title shows how many tasks there are, or the filtered number out of the total. The search runs once you stop typing and clears at once. Mine or All and the sort are remembered. The list follows task changes live; if loading fails, the tasks already shown stay and the error shows a Trace ID you can copy.

## ja

タスクページはウェブ版と同じく、検索・絞り込み・並べ替えをサーバーで行うようになりました。ツールバーの先頭は「自分」と「すべて」（件数付き）で、続いて中央いっぱいの検索欄、「チャンネル」「ステータス」「作成者」「担当者」の絞り込み、「すべてクリア」、並べ替え（更新が新しい順・更新が古い順・作成が新しい順）、リスト / ボードの切り替えが並びます。「ステータス」は複数選べます。リストでは「自分」が「あなたの担当」と「あなたが依頼」の 2 つに分かれ、それぞれ件数が表示されます。「すべて」は 1 つのリストです。ページの見出しには件数が、絞り込み中は「絞り込み結果 / 全体」が表示されます。検索は入力が止まってから実行され、空にするとすぐ元に戻ります。「自分 / すべて」と並べ替えは記憶されます。タスクの変更はリストにすぐ反映され、読み込みに失敗しても表示中のタスクは残り、コピーできる Trace ID が表示されます。
