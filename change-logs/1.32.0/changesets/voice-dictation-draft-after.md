---
type: added
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
语音听写现支持在发起听写许可请求时附带只读的光标后草稿上下文（`draftAfter`，仅在光标/选区后存在非空白内容时发送，保留前导换行与水平空白并按 Unicode 码点安全截断至最多 200 码点），在不做客户端标点/大小写改写且不修改既有草稿的前提下提升句中插入与续写准确度；若遇到旧版服务端返回 `400 invalid_request` 则静默降级重试一次，并全面确保该字段不落盘、不缓存、不进入客户端日志、诊断与追踪上报。

## en
Voice dictation now attaches read-only post-caret draft context (`draftAfter`, sent only when non-whitespace text follows the selection end, preserving leading newlines and horizontal whitespace while normalizing safely by Unicode code points up to 200 code points) to initial permit requests so mid-draft dictation has downstream context without any client-side punctuation/casing rewriting or draft mutation. Older or rolled-back servers returning `400 invalid_request` trigger a single silent stripped retry, and `draftAfter` is never cached, persisted, logged, or emitted in diagnostics or client traces.

## ja
音声ディクテーションの初回許可リクエストで、カーソル／選択範囲直後の読み取り専用下書きコンテキスト（`draftAfter`、後続に非空白文字がある場合のみ送信し、先頭の改行と水平空白を保持したまま Unicode コードポイント単位で最大 200 コードポイントに安全正規化）を送信できるようになり、クライアント側での句読点・大文字小文字の書き換えや既存下書きの変更を行わずに途中挿入の認識精度を高めました。`400 invalid_request` を返す旧サーバーでは 1 回のみ自動的にコンテキストを省いて再試行し、`draftAfter` はキャッシュ・永続化・ログ・診断・クライアントトレースへ一切出力されません。
