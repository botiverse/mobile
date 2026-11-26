---
type: fix
area: navigation
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复从已有服务器进入「切换或创建服务器」新建服务器后，界面仍停留在旧服务器的问题：复用同窗口 `SharedHomeStore` 时，非主动切服加载（`Start`/刷新/前台恢复）不再使用上一次切服遗留的服务器 ID 反向覆盖会话选服，而是同步对齐到新创建的服务器并隔离旧服延迟响应；同时在 iOS 同窗口切换/创建服务器时直接替换到单个新壳根节点，避免新旧两个壳页叠栈。

## en

Fixed an issue where creating a new server from an existing server via the server chooser landed back on the old server: non-switch Home loads (`Start`, `Refresh`, `ForegroundRefresh`) in a reused `SharedHomeStore` no longer overwrite the session selection with a stale prior switch target and instead align to the authoritative newly selected server while rejecting stale responses from the previous server. On iOS, same-shell server creation/selection also replaces down to a single shell root instead of stacking two shell controllers.

## ja

既存のサーバーから「サーバーを切り替え・作成」で新しいサーバーを作成したあと、元のサーバーにとどまってしまう不具合を修正しました。同一ウィンドウで再利用される `SharedHomeStore` の通常読み込み（`Start`／更新／フォアグラウンド復帰）が過去の切替先サーバー ID でセッション選択を上書きしないようにし、最新の選択サーバーへ同期したうえで旧サーバーの遅延応答を破棄します。あわせて iOS の同一ウィンドウ切替・作成時にシェルルートを単一インスタンスへ置き換えるようにしました。
