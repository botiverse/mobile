---
type: improved
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 4624
rollout: full
---

## zh-CN

应用里有几处提示不再解释"为什么这里做不了"，只在你能做点什么时告诉你那一步：需要通行密钥确认的更改，现在提示"请在网页版 Raft 上进行此更改，并用通行密钥确认"；受密码保护的 PDF 提示在其他应用中打开并输入密码；当前版本无法编辑的 agent 设置提示升级 App 后再保存；在浏览器中下载 Artifact 时只说正在浏览器中打开。没有 Google Play 服务的 Android 设备上，"后台保持连接"的说明只讲开启后的效果；iPhone 上"iOS 原生导航"的说明不再出现开发用语。Agent 权限加载不了时，显示"无法加载 Agent 权限"，不再提到内部接口。电脑和运行时页面的提示与网页版一致：登录运行时、用量卡片和动态概览只说你要做的那一步；添加电脑时的名称字段不再带说明；运行时选择器不再列出此服务器不提供的运行时（已选中的除外）。只停一轮或单个子 Agent 需要升级 Computer 时，提示直接说升级；频道「静音」的说明去掉了「只影响你」。

## en

A few notices in the app no longer explain why something can't be done here; where there is something you can do, they say only that step. A change that needs your passkey now says to make it in Raft on the web and confirm it with your passkey; a password-protected PDF says to open it in another app to enter the password; an agent setting this version can't edit says to update the app to save changes; downloading from an Artifact just says it's opening in your browser. On Android devices without Google Play services, Keep connection in background describes only what it does; on iPhone, iOS native navigation no longer uses developer wording. When an agent's permissions can't load, it says so instead of naming an internal API. Computer and runtime hints now match the web: runtime sign-in, the usage card and the activity overview say only the step to take; the name field when adding a computer has no hint; and runtime pickers no longer list runtimes this server doesn't offer, unless one is already selected. Stopping a single turn or subagent on an older Computer now says to upgrade the Computer, and Mute in a channel's preferences no longer adds "Only affects you".

## ja

アプリ内のいくつかの案内で、「ここでできない理由」の説明をやめ、できることがある場合はその手順だけを伝えるようにしました。パスキーでの確認が必要な変更は「Web 版の Raft でこの変更を行い、パスキーで確認してください」と表示します。パスワードで保護された PDF は他のアプリで開いてパスワードを入力するよう案内し、このバージョンで編集できないエージェントの設定はアプリの更新を案内します。Artifact からのダウンロードはブラウザで開くことだけを伝えます。Google Play 開発者サービスのない Android 端末では「バックグラウンドで接続を維持」の説明が動作だけになり、iPhone の「iOS ネイティブナビゲーション」の説明から開発用の言葉をなくしました。エージェントの権限を読み込めないときは、内部 API の名前ではなく読み込みに失敗したことを表示します。コンピューターとランタイムの案内は Web 版と同じになり、ランタイムへのログイン、使用量カード、アクティビティの概要は行う手順だけを伝えます。コンピューター追加時の名前欄の説明はなくなり、ランタイムの選択肢には、このサーバーが提供していないランタイムを（選択中のものを除き）表示しなくなりました。古いコンピューターで 1 ターンやサブエージェントだけを止めるときはコンピューターの更新を案内し、チャンネルの「ミュート」の説明から「自分だけに適用」の一文をなくしました。
