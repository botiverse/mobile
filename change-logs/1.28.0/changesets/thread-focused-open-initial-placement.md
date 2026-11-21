---
type: fixed
area: thread
platforms: [android, ios, desktop]
pull_request: 5244
rollout: full
---

## zh-CN
修复打开带有定位消息的频道、私聊或讨论串时首帧先闪一下底部再大幅跳到目标消息的问题，缓存命中时首帧直接定位到目标消息。

## en
Fix focused channel, DM, and thread opens flashing the bottom of the conversation before jumping to the target message when cached rows are available, placing the focused message directly on Frame 1.

## ja
キャッシュ済みのチャンネル・DM・スレッドを特定メッセージにフォーカスして開く際、最初のフレームで最下部が一瞬表示されてから対象メッセージへ大きくジャンプする問題を修正し、初回フレームから対象位置に表示するようにしました。
