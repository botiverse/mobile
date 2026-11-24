---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

弱网或刚重连时打开缓存会话，两条消息之间用于补齐断层的占位行不再误用「正在加载更早的回复…」这类顶部历史文案，改为明确提示加载中间缺失的消息或回复；同时在初始刷新短暂加载期间折叠占位，避免普通加载或快速重连时在消息中间闪现空白或加载指示器。

## en

When opening a cached conversation on a slow network or right after reconnecting, inline timeline gap rows between two messages now use dedicated missing-message / missing-reply copy instead of reusing top-of-list "Loading older…" wording, and collapse during a brief initial refresh grace window so routine loads never flash blank space or a mid-timeline spinner between messages.

## ja

通信状況が不安定なときや再接続直後にキャッシュ済み会話を開いた際、メッセージ間の欠落補完行に一覧先頭用の「以前の返信を読み込み中…」ではなく間のメッセージ・返信向け文言を表示するよう修正しました。また、初回更新の短い猶予時間内は高さを確保せず折りたたむことで、通常の読み込みや素早い再接続でメッセージ間に空白やスピナーがちらつかないようにしました。
