---
type: improved
area: agents
platforms:
  - desktop
  - android
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

创建 Agent 和切换运行时时，可选的运行时列表改为由服务器提供：服务器新增的运行时（例如 Qwen Code、Qoder）不用等应用更新就会出现，网络不好时沿用上次的列表。运行时选择器按「这台 Computer 上可用」「需要更新 Raft Computer」「这台 Computer 上未安装」等分组，切换时当前运行时排在最前；未安装的运行时默认收起，在列表最后点「显示全部支持的」即可展开。Qwen Code 和 Qoder 在选择器里带「实验性」标记，在 Agent 资料和 Computer 页面上也会注明（实验性）。这个版本的应用还不支持的运行时会显示「更新应用后即可使用这个运行时」，不能选择。

## en

The runtimes you can pick when creating an agent or switching its runtime now come from the server: a runtime the server adds (such as Qwen Code or Qoder) shows up without an app update, and the last list is kept when the network is down. The runtime picker groups runtimes into Ready on this computer, Needs a Raft Computer update and Not installed on this computer, with the agent's current runtime first when switching; runtimes that aren't installed stay hidden until you tap Show All Supported at the end of the list. Qwen Code and Qoder carry an Experimental badge in the picker and read "(experimental)" on agent profiles and the Computer page. A runtime this version of the app can't set up yet says "Update the app to use this runtime" and can't be picked.

## ja

エージェントの作成やランタイムの切り替えで選べるランタイムの一覧を、サーバーから取得するようになりました。サーバーに追加されたランタイム（Qwen Code、Qoder など）はアプリを更新しなくても表示され、ネットワークにつながらないときは前回の一覧を使います。ランタイムの選択肢は「このコンピューターで使用可能」「Raft Computer の更新が必要」「このコンピューターに未インストール」などに分かれ、切り替え時は現在のランタイムが先頭に表示されます。未インストールのランタイムは最初は隠れていて、一覧の最後の「対応しているものをすべて表示」で表示できます。Qwen Code と Qoder には選択肢に「試験的」バッジが付き、エージェントのプロフィールとコンピューターのページでも（試験的）と表示されます。このバージョンのアプリでまだ設定できないランタイムには「このランタイムを使うにはアプリを更新してください」と表示され、選択できません。
