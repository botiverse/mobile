---
type: fixed
area: home
platforms:
  - android
  - ios
  - desktop
pull_request: 5475
rollout: full
---

## zh-CN

- 修复自定义分组中同一个 Agent 私信同时存在新旧两种分组记录时，首页列表出现重复键并在启动或滑动时持续闪退的问题；移动或移出分组时也会自动清理旧记录。
- 仅在已渲染到有效自定义分组时才从默认分组隐藏会话，避免已删除分组的残留记录导致会话消失。

## en

- Fixed a persistent crash when opening or scrolling Home if an agent direct message had both legacy and current custom-section placement entries; moving or removing the conversation now cleans up legacy entries as well.
- Conversations are now hidden from their default system sections only when actually shown in a live custom section, preventing orphaned placements from hiding conversations from every section.

## ja

- カスタムセクション内で同じ Agent DM に新旧両方の配置レコードが存在する場合、ホーム画面の表示やスクロール時に繰り返しクラッシュする問題を修正しました。セクションの移動・解除時に古いレコードも自動で整理します。
- 有効なカスタムセクションに実際に表示されている会話だけをデフォルトセクションから隠すようにし、削除済みセクションの残留データで会話が消える問題を防ぎました。
