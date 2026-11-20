---
type: improved
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: 5174
rollout: full
---

## zh-CN
- 消息里的应用链接卡片现在会自己更新：应用告知内容有变（例如拉取请求已合并、检查已通过）时，屏幕上的卡片会静默换成最新内容，和网页端一致；回到 App 或实时连接恢复后，这类卡片也会补读一次。更新期间保留原来的卡片，不转圈、不跳动，读取失败时也保持原样。
- 修复：消息滑出屏幕再滑回来，或离开会话再回来之后，卡片上的「刷新」没有效果，卡片一直停在旧状态（例如已完成的请求仍显示「等待审批」）。
- 读取失败的应用链接不再每次滑到都重新请求，至少间隔 30 秒。

## en
- App link cards in messages now update themselves: when the App says something changed (a pull request merged, checks passed), the card on screen quietly shows the latest, as on the web. Cards that can update this way are also read once more after you come back to the app or the realtime connection comes back. The card you see stays until the new one arrives, with no spinner and no jump, and stays as it is if the read fails.
- Fixed: Refresh on a card did nothing once its message had scrolled off screen and back, or after leaving the conversation and returning, so the card stayed on an old state (a finished request still shown as waiting for approval).
- An App link whose read failed is no longer asked for again every time you scroll past it; it waits at least 30 seconds.

## ja
- メッセージ内のアプリリンクのカードが自動で更新されるようになりました。アプリから内容の変更（プルリクエストのマージ、チェックの完了など）が伝えられると、画面上のカードが Web 版と同じように静かに最新の内容に変わります。アプリに戻ったときやリアルタイム接続が復帰したときにも、こうしたカードを一度読み直します。新しい内容が届くまでは今のカードをそのまま表示し、読み込み中の表示やレイアウトのずれはありません。読み込みに失敗した場合も表示は変わりません。
- 修正: メッセージを画面外にスクロールして戻したあとや、会話を離れて戻ったあとに、カードの「更新」が効かず、古い状態（完了済みのリクエストが「承認待ち」のままなど）が表示され続ける問題を修正しました。
- 読み込みに失敗したアプリリンクは、スクロールで通るたびに再取得されなくなり、少なくとも 30 秒あけて再取得します。
