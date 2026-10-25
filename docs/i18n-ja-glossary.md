# Raft Japanese (ja) UI Glossary

Shared terminology source of truth for Japanese UI text across platforms
(mobile `MR/ja/strings.xml` today; the web `i18n/messages/ja.ts` catalog should
reuse these exact terms when it is added). Keep this file updated whenever a
new product concept gets a Japanese rendering.

## Product concepts

| English | Japanese | Notes |
| --- | --- | --- |
| Server | サーバー | Workspace-level container; not 「鯖」/「サーバ」 (always long-vowel form) |
| Channel | チャンネル | |
| Thread | スレッド | |
| Task | タスク | Status values below |
| Message | メッセージ | |
| Direct Message / DM | ダイレクトメッセージ / DM | Long form in titles, "DM" in dense UI |
| Usage data (Share usage data) | 使用状況データ（使用状況データを共有） | Settings › Account switch; copied from the web ja catalog |
| Mention | メンション | Verb: メンションする |
| Member | メンバー | |
| Agent | エージェント | Never 智能体-style translation; katakana only |
| Human(s) | 人間 | Explicit contrast with エージェント (Members page sections) |
| Computer / Machine | コンピューター | Long-vowel MS style; daemon = デーモン |
| Per-agent memory limit | エージェントごとのメモリ上限 | Computer page warning; copied from the web ja catalog (botiverse/slock #10860) |
| Workspace | ワークスペース | |
| Attachment | 添付ファイル | |
| Saved | 保存済み | Bookmark feature |
| Pinned / Pin | ピン留め | Unpin = ピン留めを解除 |
| Follow (thread) | フォロー | Unfollow = フォロー解除 |
| Archive | アーカイブ | |
| Mute / Unmute | ミュート / ミュートを解除 | |
| Inbox | 受信トレイ | Agent-permission surface too |
| Activity | アクティビティ | |
| Reaction | リアクション | |
| Forward | 転送 | |
| Quote | 引用 | |
| Draft | 下書き | |
| Unread | 未読 | Mark as read = 既読にする |
| Read (receipt: someone else has read it) | 既読 | Not read yet = 未読 (web read_receipts_v0) |
| Notification | 通知 | |
| Join / Leave (channel, server) | 参加 / 退出 | |
| Sign in / Sign out | ログイン / ログアウト | One pair everywhere; never サインイン |
| Connected accounts | 連携アカウント | |
| Assistant (personal) | アシスタント | Web `assistant.*` catalog term |
| Sent by (assistant, on the owner's behalf) | 〜が代理送信 | "sent by Friday" = 「Friday が代理送信」; deleted = 「削除済みのアシスタントが代理送信」 (RFC 077) |
| Retracted (message, by its owner) | 取り消し | "This message was retracted." = 「このメッセージは取り消されました。」; copied from the web ja catalog `message.delegated.retracted` (RFC 077) |
| Connected Apps | 連携アプリ | OAuth surface |
| Marketplace | マーケットプレイス | |
| Built-in | 組み込み | |
| Join link | 参加リンク | |
| Billing / Plan / Usage | 請求 / プラン / 使用量 | |
| Storage | ストレージ | |
| Downloads (download centre) | ダウンロード | Settings › Storage › Downloads; a running download is ダウンロード中 |
| Appearance | 外観 | |
| Language & Region | 言語と地域 | |
| Danger Zone | 危険な操作 | |
| Release Notes | リリースノート | |
| Feedback | フィードバック | |
| Diagnostics | 診断 | |
| Licenses | ライセンス | |
| Onboarding | オンボーディング | |
| Runtime | ランタイム | Agent runtime config |
| Skill | スキル | |
| Knowledge | ナレッジ | Agent manual/knowledge base |
| Reminder | リマインダー | |
| Action card | アクションカード | Approve = 承認 |
| Scope (OAuth) | スコープ | |
| Owner / Admin | オーナー / 管理者 | |
| Role | 役割 | |
| Permission | 権限 | "No permission" = 権限がありません |
| Session | セッション | |
| Public / Private | 公開 / 非公開 | Channel visibility |

Brand names stay in Latin script: **Raft**, **Hands**, **Claude Code**,
**Kuikly**, **Markdown**, **OAuth**, **API**.

## Task statuses

| Status | Japanese |
| --- | --- |
| Todo | 未着手 |
| In Progress | 進行中 |
| In Review | レビュー中 |
| Done | 完了 |
| Closed | クローズ |

## Style rules

- Sentences use polite form (です／ます); buttons and labels use terse
  noun/verb-stem forms (保存, 削除, 再試行).
- Errors follow the pattern 「〜できませんでした」 for failures and
  「〜する権限がありません」 for permission denials; retry guidance is
  「もう一度お試しください。」.
- Sentence-final punctuation is fullwidth 「。」/「？」; ASCII placeholders,
  counts, and units keep halfwidth digits with a surrounding space
  (%1$d 件, 8 文字以上).
- ALL-CAPS English labels are rendered as normal Japanese words (zh precedent:
  `REMOVING` → 削除中).
- Language self-names stay in-language in the language menu (English, 简体中文,
  日本語).
- The `_bilingual` announcement strings keep the "<ja> / <en>" bilingual shape.
- `app_locale_request_tag` must be `ja` in the ja catalog (locale routing, not
  display text).
