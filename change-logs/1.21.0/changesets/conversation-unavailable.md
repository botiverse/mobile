---
type: changed
area: conversation
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4350
rollout: full
---

## zh-CN

打不开的对话（不存在、已删除或你没有权限，服务器对这三种情况的回答相同）现在只显示一句“这个对话你无法打开”和一个按钮，来自消息链接或打开对话时返回 404 时都一样。手机上按钮是“回到会话列表”，回到首页的会话列表；宽屏（iPad、桌面）上是“返回频道列表”。不再显示“重试”或“找不到该项目”这类提示。

## en

A conversation that can't be opened (it doesn't exist, was deleted, or you can't read it; the server gives the same answer for all three) now shows one sentence, "This conversation isn't available to you", and one button, whether it came from a message link or from opening the conversation and getting a 404. On a phone the button is "Back to conversations" and returns to the Home conversation list; on a wide window (iPad, desktop) it is "Back to channels". The "Retry" and "item could not be found" messages no longer appear for it.

## ja

開けない会話（存在しない、削除された、または閲覧できない。サーバーは 3 つとも同じ応答を返します）は、メッセージリンクから開いた場合も会話を開いて 404 が返った場合も、「この会話は開けません」という 1 文とボタン 1 つだけを表示するようになりました。スマートフォンではボタンは「会話一覧に戻る」でホームの会話一覧に戻り、広い画面（iPad、デスクトップ）では「チャンネル一覧に戻る」になります。「再試行」や「項目が見つかりません」といった表示は出なくなります。
