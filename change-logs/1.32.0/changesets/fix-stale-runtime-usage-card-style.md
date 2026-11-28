---
type: fixed
area: computers
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复运行时用量卡片/弹窗在数据「已过时」状态下的样式与对齐问题：移除会与进度条底轨撞色的浅灰铺底，改用与机架卡一致的 0.6 透明度弱化过时用量内容（保持「已过时」提示条与底部操作清晰可读），并在底部弹窗与迁移详情中为过时区块添加圆角细边框与统一的 12dp 左右内边距。

## en

Fixed styling and alignment of stale runtime account usage sheets and cards: removed the `fillMuted` backdrop that blended into `RaftMeter` tracks, dimmed stale account snapshots to 0.6 opacity (matching computer rack rows) while keeping the "Out of date" strip and footer legible, and framed stale sheet/dialog blocks with a rounded hairline container and consistent 12dp horizontal inset.

## ja

ランタイム使用量シート／カードの「最新ではありません」状態のスタイルと余白揃えを修正しました。メーターのトラックと同化していた `fillMuted` 背景を廃止し、コンピューターのラック行に合わせて古い使用量スナップショットを不透明度 0.6 で控えめに表示しつつ、警告バーとフッターを鮮明に保ち、シートやダイアログ内では角丸のヘアライン枠と統一された左右 12dp の余白を適用しました。
