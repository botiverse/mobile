---
type: fixed
area: navigation
platforms: [android, ios, desktop]
pull_request: TBD
rollout: full
---

## zh-CN
打开用讨论串自身频道 ID 构造的链接（`/channel/<threadChannelId>?msg=…`）时，不再提示「找不到」，而是按服务端返回的父频道和父消息自动跳转到父会话并打开对应讨论串，定位到目标消息。

## en
Opening a link constructed with a thread's own channel ID (`/channel/<threadChannelId>?msg=…`) now redirects to the parent conversation with the thread open at the target message instead of showing "not found".

## ja
スレッド自身のチャンネル ID で構成されたリンク（`/channel/<threadChannelId>?msg=…`）を開いた際、「見つかりません」ではなく親会話のスレッドを自動で開き、対象メッセージへ移動するように修正しました。
