---
type: changed
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

消息列顶栏的标题现在跟着滚动变化，与网页版一致：原消息还在列表顶部可见时，标题是「消息列」；把原消息滚出视野后，标题换成「发送者：原消息第一行」（只有一行，放不下时省略），滚回去又变回「消息列」。原消息只有图片时显示「图片」，有其他文件时显示「文件」。点标题会滚回原消息。顶栏第二行的频道名和 ⋯ 菜单里的「在频道中查看」会打开频道并定位、高亮原消息；原消息已删除时只打开频道；没有权限查看的频道，频道名不能点，菜单里也没有「在频道中查看」。

## en

The thread header's title now follows your scrolling, as on the web: while the original message is in view at the top, it reads "Thread"; once the original scrolls away, it shows "Sender: first line of the original" on one line (cut off if it doesn't fit), and switches back when you scroll up again. An original with only images reads "Image", one with other files "File". Tap the title to scroll back to the original. The channel name on the header's second line and View in channel in the ⋯ menu open the channel at the original message, highlighted; if the original was deleted they just open the channel, and for a channel you can't view, the name isn't a link and View in channel isn't offered.

## ja

スレッドのヘッダーのタイトルが、Web 版と同じくスクロールに合わせて変わるようになりました。元のメッセージが一番上に見えている間は「スレッド」、元のメッセージがスクロールで見えなくなると「送信者：元のメッセージの 1 行目」を 1 行で表示し（収まらない部分は省略）、戻すと「スレッド」に戻ります。画像だけのメッセージは「画像」、その他のファイルがあるものは「ファイル」と表示します。タイトルをタップすると元のメッセージまで戻ります。ヘッダー 2 行目のチャンネル名と ⋯ メニューの「チャンネルで表示」は、チャンネルを開いて元のメッセージを探し、ハイライトします。元のメッセージが削除されている場合はチャンネルを開くだけで、表示できないチャンネルではチャンネル名はリンクにならず、「チャンネルで表示」も表示されません。
