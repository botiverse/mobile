---
type: changed
area: chat
platforms:
  - android
  - ios
  - desktop
pull_request: 4666
rollout: full
---

## zh-CN

在宽屏上，从会话里打开的 Artifact（消息里的 Artifact 卡片、消息中的 Artifact 链接、讨论串或旁边各栏里的 Artifact）现在和网页版一样，显示在会话这一行里的一栏中，不再盖住整个窗口。这一栏默认在任务和文件的右边、成员资料和频道详情的左边，宽 480 点，可以拖动调整，并会被记住；空间不够时它和其他栏一起让出宽度。一次只显示一个 Artifact，再打开另一个会替换它。标题栏左上角的关闭按钮可以关掉这一栏；按返回时，会先关闭这一栏和资料栏中较晚打开的那一个。讨论串展开占满整行时，打开 Artifact 会先退出展开；如果在 Artifact 打开时展开讨论串，按返回会先退出展开。切换到别的会话时这一栏会关闭；窗口变窄、离开宽屏布局时，Artifact 会像在手机上一样作为整页重新打开。手机上的行为没有变化。从 Artifacts 主页或动态打开的 Artifact 仍然是整页。

各栏的排列方式（哪几部分放在同一栏、从左到右的顺序）现在会按设备保存，格式与网页版相同；目前还不能在应用里调整，也还不会和网页版同步。

## en

On a wide screen, an Artifact opened from a conversation (an Artifact card or link in a message, or one opened from the thread or the columns beside it) now opens in a column of the conversation's row, as on the web, instead of covering the whole window. By default the column sits right of Tasks and Files and left of member profiles and the channel details. It is 480 points wide, you can drag it to a new width, and that width is remembered; when space runs short it gives way together with the other columns. One Artifact shows at a time: opening another replaces it. The Close button at the top left of its header closes the column, and Back closes whichever of this column and the profile column was opened last. With the thread expanded to fill the row, opening an Artifact first leaves the expanded view; if you expand the thread while an Artifact is open, Back leaves the expanded view first. Switching to another conversation closes the column, and if the window narrows out of the wide layout, the Artifact reopens as a full page, as on a phone. Nothing changes on a phone. Artifacts opened from the Artifacts home or from Activity still open as a full page.

How the columns are arranged (which sections share a column and their order from left to right) is now kept on each device, in the same format as the web. It can't be changed in the app yet, and it doesn't sync with the web yet.

## ja

ワイド画面で、会話から開いた Artifact（メッセージ内の Artifact カードやリンク、スレッドや横の列から開いたもの）が、ウェブ版と同じく会話の行の中の列に表示されるようになり、ウィンドウ全体を覆わなくなりました。この列は既定ではタスクとファイルの右、メンバーのプロフィールとチャンネルの詳細の左にあり、幅は 480 ポイントです。幅はドラッグで変えられ、記憶されます。幅が足りないときはほかの列と一緒に幅を譲ります。一度に表示される Artifact は一つで、別の Artifact を開くと置き換わります。ヘッダー左上の閉じるボタンで列を閉じられます。戻るを押すと、この列とプロフィールの列のうち後から開いたほうを先に閉じます。スレッドを行いっぱいに広げているときに Artifact を開くと、まず広げた表示が解除されます。Artifact を開いたままスレッドを広げた場合は、戻るでまず広げた表示が解除されます。別の会話に切り替えるとこの列は閉じ、ウィンドウが狭くなってワイドレイアウトでなくなると、Artifact はスマートフォンと同じく全画面のページとして開き直されます。スマートフォンでの動作は変わりません。Artifacts のホームやアクティビティから開いた Artifact は、これまでどおり全画面で開きます。

列の並び方（どのセクションを同じ列にまとめるか、左から右への順序）は、ウェブ版と同じ形式で端末ごとに保存されるようになりました。アプリ内ではまだ変更できず、ウェブ版との同期もまだ行われません。
