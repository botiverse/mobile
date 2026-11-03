---
type: improved
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4133
rollout: full
---

## zh-CN

在平板和 Compose 版的宽屏布局中，列表、会话、帖子回复和“任务和文件”现在与网页版一样排在同一行：从左到右依次是列表、会话、帖子、任务和文件。窗口变窄时，最右边的一栏先变窄，会话始终保留至少 360 的宽度；放得下时“任务和文件”会并排显示，放不下时仍像以前一样盖在上面。在“动态”里打开一条内容时，左侧列表最多占一半宽度，会话不会比列表还窄。手机布局不变。

## en

On tablets and the Compose desktop app's wide layout, the list, the conversation, the thread and Tasks and Files now share one row, as on the web: list, conversation, thread, then Tasks and Files, left to right. As the window narrows, the rightmost column gives way first and the conversation always keeps at least 360. Tasks and Files stands beside the thread when there is room and covers it as before when there is not. With an item open in Activity, the list takes at most half the width, so the conversation is never narrower than the list. Phone layouts are unchanged.

## ja

タブレットと Compose 版のワイドレイアウトで、一覧・会話・スレッド・「タスクとファイル」が Web 版と同じく 1 行に並ぶようになりました。左から一覧、会話、スレッド、タスクとファイルの順です。ウインドウが狭くなると右端の列から先に狭くなり、会話は常に 360 以上の幅を保ちます。余裕があれば「タスクとファイル」はスレッドの横に並び、足りないときは従来どおり上に重なって表示されます。アクティビティで項目を開いているときは、一覧の幅は最大で半分になり、会話が一覧より狭くなることはありません。スマートフォンのレイアウトは変わりません。
