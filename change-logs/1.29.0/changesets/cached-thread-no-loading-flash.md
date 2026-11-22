---
type: fixed
area: thread
platforms: [android, ios, desktop]
pull_request: 5308
rollout: full
---

## zh-CN
修复打开已浏览过的频道、私聊或讨论串时首屏短暂闪烁「加载中…」的问题：后台收到新消息后再次打开会话会立即展示已有内容并在后台刷新，冷启动后快速读取本地历史时也不再短暂闪出加载提示，仅在真正需要等待网络时显示。

## en
Fix previously visited channels, DMs, and threads briefly flashing "Loading…" when opened: conversations with newer background activity now show existing messages immediately while refreshing in the background, and fast offline history reads after a cold start no longer flash a spinner before content appears.

## ja
閲覧済みのチャンネル・DM・スレッドを開く際に「読み込み中…」が一瞬ちらつく問題を修正しました。バックグラウンドで新着があった場合でも既存のメッセージを即座に表示して裏側で更新し、コールドスタート後の履歴読み込みでも不要なスピナー表示を抑えます。
