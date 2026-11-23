---
type: fixed
area: thread
platforms: [android, ios, desktop]
pull_request: 5508
rollout: full
---

## zh-CN

修复从定位消息打开会话或讨论串后，在电脑版（以及连接触控板或鼠标的平板/手机）上向下滚动无法加载更新消息、短列表不会自动加载更新消息的问题；修复加载更早消息的请求被中断或跳过时一直卡在加载中以及少量异常行导致提前停止翻页的问题；顶部“正在加载更早的…”仅在实际加载时显示，并在讨论串中与网页端一致显示为“正在加载更早的回复…”。

## en

Fixed scrolling down with a trackpad or mouse wheel (and short anchored lists too short to scroll) not loading newer messages after jumping to a message; fixed dropped or cancelled older-page requests leaving older loading stuck and row drops or overlaps ending pagination early; and aligned the top history notice with web so "Loading older…" only shows while loading and threads say "Loading older replies…".

## ja

メッセージへジャンプして開いた会話やスレッドで、トラックパッドやマウスホイールで下へスクロールしても新しいメッセージが読み込まれない問題（およびスクロールできないほど短いリストで自動読み込みされない問題）を修正しました。あわせて、以前のメッセージの読み込みが中断・破棄された際に読み込み中のまま固まる問題や、一部行のスキップ・重複でページングが途中で止まる問題を修正し、上部の「読み込み中…」は実際に読み込み中のときのみ表示され、スレッドでは Web 版と同様に「以前の返信を読み込み中…」と表示されるようにしました。
