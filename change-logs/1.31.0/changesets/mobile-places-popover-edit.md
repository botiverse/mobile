---
type: feat
area: navigation
platforms:
  - android
  - ios
rollout: flag
---

## zh-CN

在 `tab_bar_places_v0` 实验开关开启时，升级手机端服务器标签弹层：新增「最近使用」（最多展示 4 条最近打开的 Artifact 或 Raft App，无记录时整段隐藏）与「更多」分组标题和「编辑」入口，并在弹层内直接提供「编辑导航栏」模式，支持通过拖动、−/+ 按钮或无障碍操作自定义底部标签栏与「更多」网格顺序（设置固定在最后，重置仅恢复移动端默认顺序）。Android 端另支持长按底部导航栏中的任一地方标签直接进入编辑模式；开关关闭与桌面端保持原有体验不变。

## en

When `tab_bar_places_v0` is enabled on phones, upgraded the Server Tab Popover with a "Recently used" section (up to 4 recently opened Artifacts and Raft Apps, omitted when empty), a "More" header with an "Edit" action over the 4-column overflow grid, and an in-popover "Edit navigation bar" mode supporting drag-and-drop, `−`/`+` controls, accessibility move actions, live bottom-bar updates, and mobile-only Reset (with Settings kept last). On Android, long-pressing any place tab on the bottom bar opens the popover directly in edit mode; desktop and flag-off behavior are unchanged.

## ja

`tab_bar_places_v0` が有効なモバイル端末向けにサーバータブのポップオーバーを拡張し、「最近使った項目」（最近開いた Artifact / Raft App を最大 4 件表示、履歴がない場合は非表示）、「その他」見出しと「編集」ボタン、およびポップオーバー内の「ナビゲーションバーを編集」モードを追加しました。ドラッグ、−/+ ボタン、アクセシビリティ操作でタブバーと「その他」グリッドの順番をリアルタイムに編集でき（設定は末尾に固定、リセットはモバイル設定のみを初期化）、Android ではボトムバーの各タブの長押しでも直接編集モードを開けます。フラグ無効時およびデスクトップ版への影響はありません。
