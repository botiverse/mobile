---
type: fixed
area: agents
platforms:
  - desktop
  - android
  - ios
pull_request: https://github.com/botiverse/mobile/pull/4003
rollout: full
---

## zh-CN

创建 Agent 时默认选中的运行时与网页版一致：只会从所选 Computer 上已安装、且你可以使用的运行时里选，优先上次创建 Agent 时用的运行时，否则选第一个；不会再默认选中「未安装」或「需要更新 Raft Computer」的运行时，没有可用的运行时时就不预先选择。Computer 页面和添加 Computer 时显示的运行时列表，现在始终使用当前服务器的列表，冷启动时也能立刻显示正确的名称。运行时选项较少时，「显示全部支持的」与上方的选项左对齐。

## en

The runtime a new agent starts on now matches the web: it is picked only from the runtimes installed on the chosen computer that you can use, preferring the one you used for your last new agent, otherwise the first. A runtime that isn't installed or needs a Raft Computer update is no longer pre-selected, and nothing is pre-selected when none is available. The runtimes on a Computer's page and in Add Computer always come from the current server's list, with the right names straight after a cold start. When only a few runtimes are listed, Show All Supported now lines up with the options above it.

## ja

エージェント作成時に最初に選ばれるランタイムが Web 版と同じになりました。選んだコンピューターにインストール済みで、あなたが使えるランタイムの中から、前回エージェントを作成したときのランタイムを優先し、なければ先頭のものを選びます。「未インストール」や「Raft Computer の更新が必要」なランタイムが最初から選ばれることはなくなり、使えるランタイムがないときは何も選ばれません。コンピューターのページとコンピューター追加画面のランタイム一覧は、常に現在のサーバーの一覧を使い、コールドスタート直後から正しい名前で表示されます。ランタイムの選択肢が少ないとき、「対応しているものをすべて表示」が上の選択肢と左揃えになりました。
