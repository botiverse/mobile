---
type: fixed
area: navigation
platforms:
  - android
pull_request: 5275
rollout: full
---

## zh-CN
修复了 Android「多应用窗口」的 3 项问题：进程被系统清理后切换回已有工作空间不再多开重复窗口、从最近任务重新打开后台工作空间窗口时不再重放旧通知或闪烁其他工作空间的主题和动态，以及从「选择或创建工作空间」或邀请加入页切换工作空间时不再覆盖原窗口或重复开窗。

## en
Fixed three Android "Multiple app windows" issues: switching to an existing workspace after process death now revives its Recents task instead of spawning a duplicate window, reopening a background workspace from Recents no longer replays stale notifications or flashes another workspace's theme or activity, and picking or creating a workspace from Choose Workspace or Server Join no longer mutates the originating window or opens duplicate windows.

## ja
Android の「複数のアプリウィンドウ」における 3 件の不具合を修正しました。プロセス終了後に既存ワークスペースへ切り替えても重複ウィンドウを作成せず、最近使ったアプリから再開しても古い通知の再実行や他ワークスペースのテーマ・アクティビティのちらつきが起きないようになり、ワークスペースの選択・作成や招待参加ページから切り替えた際に元のウィンドウを上書きしたり重複ウィンドウを開いたりしないようにしました。
