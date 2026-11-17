---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

在智能体的运行时设置中，如果计算机读不到模型列表是因为运行时没有登录，或它的命令行工具版本太旧，下方会直接说明「此运行时在这台 Computer 上没有登录。」或「这台 Computer 上的运行时命令行工具版本太旧，无法列出可选项。请在那里更新。」，不再只显示「无法加载可选项」，与网页版一致。

## en

In an agent's runtime settings, when the Computer can't list the models because the runtime isn't signed in there or its command-line tool is too old, the line under the field now says so ("This runtime is not signed in on this Computer." or "This runtime's command-line tool on this Computer is too old to list the options. Update it there.") instead of the generic "The options could not be loaded.", as on the web.

## ja

エージェントのランタイム設定で、ランタイムがログインしていない、またはコマンドラインツールが古いためにコンピューターがモデルを一覧表示できない場合、項目の下に「このランタイムはこのコンピューターでログインしていません。」または「このコンピューターのランタイムのコマンドラインツールが古いため、選択肢を一覧表示できません。そちらで更新してください。」と表示されるようになりました。これまでの「選択肢を読み込めませんでした。」の代わりで、ウェブ版と同じ動作です。
