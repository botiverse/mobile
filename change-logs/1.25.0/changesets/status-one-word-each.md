---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4637
rollout: full
---

## zh-CN

状态在各处只用一套说法，与网页版一致：智能体工作时显示「工作中…」「思考中…」；电脑在线写「在线」，不再写「已连接」；正在更新的电脑亮黄灯并显示「更新中…」和「正在更新到 v…」，留在原位而不是显示为离线；有可用更新和需要更新都用橙色、都算需要处理，分别写「有可用更新」和「需要更新」（旧版守护进程的提醒也改用这套说法）。因所在电脑版本过旧而无法运行的智能体显示「Computer 需要更新」，点资料页的状态即可打开那台电脑；电脑离线时，资料页的动态显示「Computer 离线」，版本一栏显示「—」。需要更新电脑的运行时标签写「（需要更新）」并显示为橙色。尚未登录的外部智能体显示橙色状态灯。成员列表中每个智能体旁边显示简短的状态。运行时登录状态写「已登录」或「未登录」，不再带小圆点；额度用完时写何时恢复。Android「Live Agents」小组件使用相同的状态用词（跟随应用语言）和方形状态灯。

## en

Status now reads the same everywhere, as on the web: agents say "Working…" and "Thinking…"; a Computer that is connected says "Online", not "Connected"; a Computer that is updating shows a yellow light with "Updating…" and "Updating to v…" and stays in place instead of reading Offline; "Update available" and "Update required" are both orange and both count as needing attention (the legacy daemon notice uses the same words). An agent whose Computer is too old reads "Computer update required", and its profile status opens that Computer; while the Computer is offline, the profile's activity reads "Computer offline" and its version row "—". A runtime that needs a newer Computer reads "(update required)" in an orange chip. An external agent still waiting for sign-in has an orange light. Each agent in the Members list shows its short status. A runtime's sign-in reads "Signed in" or "Signed out" without a dot, and used-up usage says when it is back. The Android Live Agents widget uses the same status words, in the app's language, with square status lights.

## ja

ステータスの言い方をウェブ版と同じ一つに揃えました。エージェントは「作業中…」「思考中…」と表示されます。接続中のコンピューターは「接続中」ではなく「オンライン」と表示されます。更新中のコンピューターは黄色のランプと「更新中…」「v… にアップデート中」を表示し、オフライン扱いにならずその場に残ります。「アップデートあり」と「アップデートが必要」はどちらもオレンジで、対応が必要なものとして数えられます（旧デーモンの通知も同じ言い方になりました）。コンピューターのバージョンが古すぎて動けないエージェントは「コンピューターのアップデートが必要」と表示され、プロフィールのステータスからそのコンピューターを開けます。コンピューターがオフラインのときは、プロフィールのアクティビティに「コンピューターがオフライン」、バージョン欄に「—」と表示されます。新しいコンピューターが必要なランタイムはオレンジのチップで「（アップデートが必要）」と表示されます。サインインを待っている外部エージェントのランプはオレンジです。メンバー一覧では各エージェントの短いステータスを表示します。ランタイムのログイン状態は点なしで「ログイン済み」「未ログイン」と表示し、使用量を使い切ったときはいつ戻るかを表示します。Android の「Live Agents」ウィジェットも同じステータスの言葉（アプリの言語）と四角いランプを使います。
