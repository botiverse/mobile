---
type: changed
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

在平板等宽屏布局中，会话旁边的每个侧栏（回复串、成员资料、频道详情及其成员列表、Artifact）的标题栏末尾都有关闭按钮（×）：它只关闭这个侧栏，会话保持原样，不会退回到别的页面。只有在侧栏里又打开了一页（例如从频道详情打开成员列表）时，标题栏开头才会显示返回按钮（‹），它回到侧栏里的上一页；其他时候只显示 ×。回复串侧栏原来开头的返回箭头改为末尾的 ×，成员资料和 Artifact 的关闭按钮也从左上角移到了右上角，与频道详情一致。系统返回键、Esc 和 Cmd+[ 的行为不变：先作用于最后打开的侧栏，每按一次关闭它最上面的一页。

## en

In the wide layout on tablets and desktops, every pane beside the conversation (a reply thread, a member's profile, the channel details and their member list, an Artifact) now ends its header with a Close button (×) that closes that pane only: the conversation stays where it is and you never step back to another page. A Back button (‹) appears at the start of the header only when you opened another page inside the pane, such as the member list from the channel details, and it returns to the previous page in that pane; otherwise the header shows × alone. The reply thread's Back arrow is now a × at the end of its header, and the Close button of profiles and Artifacts moved from the top left to the top right, as on the channel details. System Back, Esc and Cmd+[ work as before: they act on the pane opened last first, closing its top page with each press.

## ja

タブレットやデスクトップのワイドレイアウトで、会話の横に開くペイン（返信スレッド、メンバーのプロフィール、チャンネル詳細とそのメンバー一覧、Artifact）のヘッダー末尾に閉じるボタン（×）が付きました。押すとそのペインだけが閉じ、会話はそのまま残り、別のページに戻ることはありません。ペインの中でさらにページを開いたとき（チャンネル詳細からメンバー一覧を開いたときなど）だけ、ヘッダーの先頭に戻るボタン（‹）が表示され、ペイン内の前のページに戻ります。それ以外は × だけが表示されます。返信スレッドの先頭にあった戻る矢印は末尾の × になり、プロフィールと Artifact の閉じるボタンはチャンネル詳細と同じく左上から右上に移りました。システムの戻る操作、Esc、Cmd+[ はこれまでどおり、最後に開いたペインに先に働き、押すたびにその一番上のページを閉じます。
