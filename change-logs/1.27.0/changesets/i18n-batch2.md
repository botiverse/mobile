---
type: fixed
area: i18n
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

几处还显示英文的地方现在会跟随你的界面语言：
- 设置里的翻译模式（自动 / 手动 / 关闭）、翻译目标语言列表里的各种语言名称，以及消息字号（小 / 中 / 大）。
- 转发消息时：转发失败的提示、“有几条消息无法转发，已跳过”的提醒，以及打开一组转发消息后页面顶部的标题。遇到未知错误时，提示会显示“无法转发”，不会再直接显示错误代码。
- 点通知打不开对应的服务器或会话时的提示；应用内新消息横幅在缺少标题或内容时的默认文字；从其他 app 分享到 Raft 时，选择服务器那一栏的标题。
- Agent 活动日志：工具调用（如“正在运行命令”“正在读取文件”）、没有标题的条目、工具输入的摘要，以及 Agent 先读新消息再发送或处理任务时的说明（如“发送前先读新消息”“#general 里有 3 条新消息”）。Agent 状态里的“消息已投递，运行时未响应”也会显示为你的界面语言。

## en

A few places that still showed English now follow your language:
- In Settings: the translation modes (Auto / Manual / Off), the language names in the translation language list, and the message font sizes (Small / Medium / Large).
- When forwarding messages: the messages shown when a forward fails, the note that some selected messages can't be forwarded and were skipped, and the title of a forwarded messages page. An unknown error now shows "Unable to forward" instead of an error code.
- The message shown when a notification's server or conversation can't be opened, the default text of the in-app new message banner when a notification arrives without a title or text, and the Server label when you share to Raft from another app.
- In an agent's activity log: tool calls (such as "Running command" or "Reading file"), rows without a title, the summary of a tool's input, and the rows where the agent reads new messages before it sends or updates a task (such as "Reading new messages before sending" and "3 new messages in #general"). The agent status "Messages delivered, runtime not responding" follows your language too.

## ja

英語のまま表示されていたいくつかの箇所が、表示言語で表示されるようになりました：
- 設定の翻訳モード（自動 / 手動 / オフ）、翻訳先の言語一覧の言語名、メッセージの文字サイズ（小 / 中 / 大）。
- メッセージの転送：転送に失敗したときのメッセージ、選択したメッセージの一部を転送できずにスキップしたことの案内、転送されたメッセージのページのタイトル。不明なエラーのときは、エラーコードではなく「転送できません」と表示します。
- 通知からサーバーや会話を開けなかったときのメッセージ、タイトルや本文のない通知を受け取ったときのアプリ内の新着メッセージバナーの既定の文言、他のアプリから Raft に共有するときのサーバー欄の見出し。
- エージェントのアクティビティログ：ツールの呼び出し（「コマンドを実行中」「ファイルを読み込み中」など）、タイトルのない行、ツール入力の概要、エージェントが送信やタスクの更新の前に新しいメッセージを読む行（「送信する前に新しいメッセージを読んでいます」「#general に新しいメッセージが 3 件」など）。エージェントの状態「メッセージは配信済みですが、ランタイムが応答していません」も表示言語で表示されます。
