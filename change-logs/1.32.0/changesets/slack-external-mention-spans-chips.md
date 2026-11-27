---
type: added
area: chat
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
支持渲染来自 Slack 桥接消息的外部成员提及芯片（`externalMentionSpans`）：按消息正文中精确的 UTF-16 偏移位置展示外部成员提及，保持原有 `mentions` 字段不变；跳过代码块、行内代码、链接与引用块中的普通文本，兼容转义字符与代码样式标签；悬停或点按芯片时展示对应 Slack 外部身份卡片（并在本地成员列表缺省时回退展示原始标签），不会误跳转到 Raft 成员主页。

## en
Added rendering for Slack-bridged external member mention chips (`externalMentionSpans`): decodes and preserves `externalMentionSpans` across REST, realtime, sync resume, and message merge/cache paths without altering `mentions`, validates exact UTF-16 source offsets against the parsed Markdown AST (skipping code blocks, arbitrary inline code, links, and blockquotes while unwrapping escaped and code-span labels), and renders each chip with an external identity preview card (falling back to the label when absent from the channel's external roster) instead of opening a Raft profile.

## ja
Slack ブリッジ経由メッセージの外部メンバーメンションチップ（`externalMentionSpans`）の表示に対応しました。既存の `mentions` を変更せずに REST・リアルタイム・同期レジュームおよびキャッシュ／マージ経路で `externalMentionSpans` を保持し、パース済み Markdown AST 上で正確な UTF-16 オフセットを検証（コードブロック・通常のインラインコード・リンク・引用内を除外しつつエスケープやコードスパン形式のラベルを正しく展開）してチップとして描画します。ホバーやタップ時には Raft プロフィールではなく外部 ID プレビューカードを表示し、チャンネルの外部メンバー一覧に未収録の場合は元のラベルにフォールバックします。
