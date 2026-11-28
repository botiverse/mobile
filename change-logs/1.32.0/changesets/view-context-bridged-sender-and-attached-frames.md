---
type: added
area: agents
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
在 Agent 上下文查看（View Context）面板中实现与服务端/Web 端一致的投递通知解析与展示（对应 `slock` `#17276`、`#17284`、`#17300`、`#17306`、`#17311`、`#17316`）：跳过 `<memory-index>` 等 Raft 引用块避免误识别为通知；外部/Slack 桥接发送者按“姓名（来源）”展示而不渲染为 `@` 句柄；仅在消息头明确携带 `frames=1` 时解析附带帧（发送者简介、附件、任务、任务讨论区与自由键值），以纯文本只读行展示在正文下方，将自由键值包装为“其他（key）”防止伪造 Raft 标签，并以计数汇总被丢弃或未知的附带项。

## en
Added structured Raft delivery notice parsing and rendering parity in Agent View Context (`slock` `#17276`, `#17284`, `#17300`, `#17306`, `#17311`, `#17316`): skips Raft quoted blocks (such as `<memory-index>`), renders external/Slack-bridged senders by display name and source (`Name (Slack)` / `Name (External)`) instead of an empty `@` handle, parses attached frames strictly under headers carrying `frames=1`, displays sender description, attachments, tasks, and task conversation as read-only text rows below the message body, labels free-text value frames as `Other (key)` so they cannot spoof Raft labels, and summarizes dropped or unrecognized frames by count only.

## ja
Agent コンテキスト表示（View Context）で、Web と同等の Raft 配信通知のパースと表示に対応しました（`slock` `#17276`・`#17284`・`#17300`・`#17306`・`#17311`・`#17316`）：`<memory-index>` などの Raft 引用ブロック内の疑似ヘッダーを通知として誤認識しないよう除外し、Slack などの外部ブリッジ送信者を `@` ハンドルではなく「名前（ソース）」形式で表示します。また、ヘッダーに `frames=1` が明示されているメッセージに限り付帯フレーム（送信者の説明・添付ファイル・タスク・タスクの会話・自由キー値）を本文下に読み取り専用のテキスト行として表示し、自由キーは Raft ラベルと誤認されないよう「その他（key）」として表示し、除外・未対応のフレームは件数のみを表示します。
