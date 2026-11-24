---
type: improved
area: artifacts
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

支持在 Artifact 首页与消息卡片的右键/长按菜单中直接关注或取消关注 Artifact；在「与我相关」视图下取消关注会保留灰显条目并附带「已取消关注」标记与底部撤销提示，后台刷新期间仍可原地撤销。宽屏下在 Artifact 首页点击条目会在右侧分栏直接打开查看器并高亮当前条目，同时为 Artifact 查看器工具栏接入原生分享链接菜单项。

## en

Added Follow and Unfollow actions to Artifact home tiles, list rows, and message card context menus. Unfollowing under "Relevant to me" keeps the item dimmed in place with an "Unfollowed" badge and an Undo toast across background refreshes. On wide screens, opening an Artifact from the Artifacts home now displays the viewer in a side-by-side split column with active selection state, and the embedded viewer now exposes a native Share link action via `hostMenu=share-link`.

## ja

Artifact ホームやメッセージカードのコンテキストメニューから直接フォロー／フォロー解除できるようになりました。「自分に関連」表示でフォロー解除すると「フォロー解除済み」バッジ付きで薄暗く保持され、バックグラウンド更新中もその場で元に戻せます。ワイド画面では Artifact ホームから開いた際に右側の分割カラムでビューアーを表示して選択中の項目を強調表示し、ビューアーにはネイティブのリンク共有アクション（`hostMenu=share-link`）も追加しました。
