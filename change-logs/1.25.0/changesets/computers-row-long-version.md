---
type: fixed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 4704
rollout: full
---

## zh-CN

计算机页面中，版本号很长的计算机（如测试版 `v1.25.0-staging.…`）不再把图标和名称挤出行外：列表模式下图标、名称和 Agent 数量始终排在最前，放不下的版本号会完整显示在下一行，「有可用更新」等提示也完整显示；卡片模式中过长的版本号同样换行显示，不再被截断。

## en

On the Computers page, a computer with a very long version (such as a staging build's `v1.25.0-staging.…`) no longer pushes its icon and name off the row: in List view the icon, name and agent count always come first, and a version that doesn't fit beside them is shown in full on the next line, with "Update available" and similar notices whole; Cards view wraps a long version the same way instead of cutting it.

## ja

コンピューターページで、バージョンが非常に長いコンピューター（ステージングビルドの `v1.25.0-staging.…` など）が、アイコンと名前を行の外に押し出さなくなりました。リスト表示ではアイコン・名前・エージェント数が常に先頭に表示され、横に収まらないバージョンは次の行に省略なしで表示されます。「アップデートあり」などの通知も省略されません。カード表示でも長いバージョンは切り詰めずに折り返して表示されます。
