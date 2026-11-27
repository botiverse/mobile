---
type: added
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
语音听写现支持在发起听写许可请求时附带只读的光标前草稿上下文（`draftBefore`，Unicode 码点安全截断至最多 500 码点）以及当前服务器范围内的本地高频联系人、智能体与频道词汇提示（`contactHints`，零额外网络请求、最多 20 项），在不修改既有草稿内容的前提下提升专有名词与上下文转写准确度；若遇到旧版服务端返回 `400 invalid_request` 则静默降级重试一次，并全面确保这两项字段不落盘、不缓存、不进入客户端日志与追踪上报。

## en
Voice dictation now attaches read-only pre-caret draft context (`draftBefore`, normalized safely by Unicode code points up to 500 code points) and strictly server-scoped local contact, agent, and channel vocabulary hints (`contactHints`, collected synchronously from in-memory stores with zero extra network calls, capped at 20 entries) to initial permit requests to improve proper-noun and contextual transcription accuracy without mutating pre-existing draft text. Older or rolled-back servers returning `400 invalid_request` trigger a single silent stripped retry, and both fields are never cached, persisted, logged, or emitted in client traces.

## ja
音声ディクテーションの初回許可リクエストで、カーソル直前の読み取り専用下書きコンテキスト（`draftBefore`、Unicode コードポイント単位で最大 500 コードポイントに安全正規化）および現在のサーバー内に限定されたローカルの連絡先・エージェント・チャンネル語彙ヒント（`contactHints`、追加ネットワーク通信なしでメモリ内ストアから同期収集、最大 20 件）を送信できるようになり、既存の下書きを変更せずに固有名詞や文脈の認識精度を高めました。`400 invalid_request` を返す旧サーバーでは 1 回のみ自動的にヒントを省いて再試行し、両フィールドはキャッシュ・永続化・ログ・クライアントトレースへ一切出力されません。
