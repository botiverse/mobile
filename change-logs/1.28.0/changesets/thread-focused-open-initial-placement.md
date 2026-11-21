---
type: fix
platforms: [android, ios]
pull_request: 0
---

## zh-CN

- 修复打开带有定位消息的频道、私聊或讨论串时首帧先显示底部再大幅跳到目标消息的问题，缓存命中时首帧直接停在目标消息位置。
- 修复点击“回到底部”等显式跳转控件时被打开稳定性追踪误记为异常回跳的问题。

## en

- Fix focused channel, DM, and thread opens flashing the bottom of the conversation before jumping to the target message when cached rows are available, placing the focused message directly on Frame 1.
- Treat explicit non-drag user navigation controls (such as tapping Back to bottom) as user takeover in thread-open tracing instead of recording them as unexpected open jumps.

## ja

- キャッシュ済みのチャンネル・DM・スレッドを特定メッセージにフォーカスして開く際、最初のフレームで最下部が表示されてから対象メッセージへ大きくジャンプする問題を修正し、初回フレームから対象位置に表示するようにしました。
- 「最下部へ戻る」などの明示的なユーザー操作をスレッドオープン計測でユーザー操作による引き継ぎとして扱い、意図しないジャンプとして誤計上されないようにしました。
