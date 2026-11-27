---
type: fixed
area: artifacts
platforms: [android, ios]
rollout: full
---

## zh-CN
Artifact 消息卡片收到新版本但新封面图尚未生成时，现会保留原 16:10 预览占位框防止会话布局跳动，并在页面可见时每 15 秒自动轮询最新封面（最长保留 10 分钟，或在新封面/Markdown 摘要到达后立即切换）。

## en
When an Artifact message card receives a new version before its preview image is ready, it now holds the 16:10 preview box to avoid layout jumps and re-polls the latest summary every 15 seconds while visible (up to 10 minutes, or until the new screenshot or Markdown excerpt arrives).

## ja
Artifact メッセージカードで新しいバージョンのプレビュー画像がまだ届いていない間も 16:10 のプレビュー枠を維持してレイアウトの揺れを防ぎ、画面表示中は 15 秒ごとに最新情報を再取得するようにしました（最大 10 分、または新しい画像・Markdown 抜粋が届き次第切り替え）。
