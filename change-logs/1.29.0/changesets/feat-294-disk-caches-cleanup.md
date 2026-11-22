---
type: changed
area: performance
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5414
---

## zh-CN
清理旧版头像本地缓存目录与不再使用的数据库表，并为消息中的 Artifact 摘要卡片、截图预览和 App 链接卡片增加本地磁盘缓存，冷启动或重新打开对话时可立即展示上次内容并在后台静默刷新。

## en
Clean up legacy on-disk avatar cache files and retired database tables, and add on-disk caching for Artifact summary cards, Artifact screenshots, and App link preview cards so conversations paint cached previews immediately on reopen and refresh quietly in the background.

## ja
旧アバターキャッシュと不要なデータベーステーブルを整理し、Artifact サマリーカード・スクリーンショット・アプリリンクプレビューのディスクキャッシュを追加しました。再起動や会話の再表示時に前回のプレビューを即座に表示し、バックグラウンドで最新化します。
