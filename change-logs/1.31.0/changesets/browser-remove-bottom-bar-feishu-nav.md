---
type: improved
area: browser
platforms: [android, ios]
rollout: all
---

## zh-CN
精简了应用内浏览器的导航交互，去除底部多余操作栏：
- 彻底移除了应用内浏览器底部的「后退 / 前进 / 刷新 / 关闭」工具栏，不再与 Stamp 等内置应用自身的底部导航栏叠层，刷新与彻底关闭页面统一保留在右上角 `⋮` 菜单中。
- 顶部左上角 `←` 与系统返回手势对齐为自然导航栈：有网页历史时先逐层返回上一页，退到首页时再回到 Raft（应用收进右侧浮标）；进入子页产生历史后，左上角 `←` 旁会出现紧凑的 `×`，方便随时一键直回 Raft。

## en
Streamlined in-app browser navigation by removing the redundant bottom toolbar:
- Removed the bottom Back / Forward / Reload / Close strip so installed Raft Apps (such as Stamp) keep full ownership of the bottom edge without stacked bottom bars; Reload and Close page stay in the top-right `⋮` overflow menu.
- Aligned the top-left `←` button with system Back navigation: steps back through WebView history first and returns to Raft (suspending the App to its floating pill) at root, with a compact `×` appearing beside `←` once web history exists for 1-tap return to Raft.

## ja
アプリ内ブラウザ下部の重複ツールバーを削除し、ナビゲーションを整理しました：
- 画面下部の「戻る / 進む / 再読み込み / 閉じる」バーを廃止し、Stamp などの組み込みアプリ自身のボトムナビゲーションと重ならないようにしました（再読み込み・ページを閉じる操作は右上の `⋮` メニューに集約）。
- 左上の `←` をシステム戻る操作と同じ自然な履歴ナビゲーションに統一しました（履歴がある場合は前のページへ戻り、先頭ページで Raft に復帰。サブページ閲覧中は `←` の横にコンパクトな `×` を表示し、ワンタップで Raft に戻れます）。
