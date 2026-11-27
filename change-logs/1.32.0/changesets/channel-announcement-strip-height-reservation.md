---
type: improved
area: channels
platforms: [android, ios]
rollout: full
---

## zh-CN
打开含置顶公告的频道或私聊时，顶部公告栏会在内容加载完成前预先保留高度，避免聊天消息列表在公告出现时上下跳动；若加载超过 1 秒会显示柔和的骨架占位，加载失败时自动收起且重试不再抖动。

## en
Opening a channel or direct message with a pinned announcement now reserves space for the announcement bar before its content loads, preventing the message timeline from jumping. Slow loads over 1 second show a quiet skeleton placeholder, and failed loads collapse cleanly without bouncing on retries.

## ja
ピン留めお知らせのあるチャンネルや DM を開いたとき、読み込み完了前から上部のお知らせバーの高さを確保し、メッセージ一覧のガタつきを防ぎます。読み込みが 1 秒を超える場合は控えめなスケルトンを表示し、失敗時は静かに畳んで再試行時の揺れを防ぎます。
