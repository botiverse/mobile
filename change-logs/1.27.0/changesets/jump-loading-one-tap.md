---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

「回到底部」（以及「N 条新消息」）现在点一次就够了。跳到较早的消息（搜索结果、链接、未读）之后，最新的消息需要先加载：按钮会显示「正在加载消息…」，加载期间再点不会重复请求，加载完成后列表直接滚到底部。按钮上下边缘附近的点按也算数，不会再落到下面的消息列表上。加载失败时会提示错误（带 Trace ID），按钮恢复原样，再点一次即可重试。跳到还没加载的消息时（未读消息列、消息列时间轴、搜索结果、消息链接、文件的「跳转到原消息」），加载期间同样会显示加载中，重复点按不会重新开始加载，失败时也会提示。

## en

"Back to Bottom" (and "N new messages") now works with one tap. After you jump to an older message (a search result, a link, the first unread), the newest messages have to load first: the button shows "Loading Messages…", more taps while it loads send no extra requests, and the list goes straight to the bottom once they arrive. A tap just above or below the button now counts too, instead of landing on the messages underneath. If loading fails, an error message (with its Trace ID) says so and the button comes back, so one more tap retries. Jumping to a message that isn't loaded yet (unread threads, the thread timeline, search results, message links, a file's "Jump to Original Message") shows loading the same way, a repeated tap doesn't restart the load, and a failure is reported.

## ja

「最下部に戻る」（と「新着メッセージ N 件」）が 1 回のタップで動くようになりました。古いメッセージ（検索結果、リンク、未読）に移動したあとは最新のメッセージを先に読み込む必要があり、そのあいだボタンに「メッセージを読み込み中…」と表示されます。読み込み中にもう一度タップしてもリクエストは重複せず、読み込みが終わるとそのまま最下部まで移動します。ボタンのすぐ上や下をタップしても、下にあるメッセージ一覧ではなくボタンが反応するようになりました。読み込みに失敗した場合はエラー（Trace ID 付き）が表示され、ボタンが元に戻るので、もう一度タップすると再試行できます。まだ読み込まれていないメッセージへの移動（未読スレッド、スレッドのタイムライン、検索結果、メッセージリンク、ファイルの「元のメッセージに移動」）でも同じように読み込み中が表示され、繰り返しタップしても読み込みはやり直されず、失敗した場合も表示されます。
