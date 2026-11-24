---
type: changed
area: settings
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

同步 1.30 网页端体验细节与文案清理：

- **Computer 运行时检测重试**：当运行时检测处于进行中或未完成时，详情页会在运行时标签上方展示状态；点击「重试」会保持检测中状态直到 Computer 返回新结果或超时失败，并将三处旧的「重新扫描运行时」提示更新为重启该机器上的 Raft Computer。
- **运行时账号用量自动检查**：打开用量卡片或返回应用时仅在距上次更新满 2 分钟、过期或缺失时自动检查，不再默认展示手动刷新按钮；2 分钟后才提供「重新检查」，离线、超时或失败时内联提示并释放冷却窗口。
- **Agent 工作区刷新体验**：宽屏与桌面端在工作区标题右侧改为图标刷新按钮，手机端改为下拉刷新并更新对应提示；同一台 Computer 的扫描请求会自动去重、会话内重新打开保留上次扫描列表，失败时在列表上方展示带「重试」的警告横幅。
- **频道设置 Slack 频道列表自动读取**：打开频道详情的 Slack 频道下拉菜单或切回应用时自动读取列表（共享每分钟一次的限频保护），加载时在菜单顶部提示「正在读取 Slack 频道…」，失败时展示带「重试」的警告横幅。
- **图标、卡片与文案细节**：退出频道与退出服务器统一改为开门图标（退出登录保持不变）；操作卡片移除多余的「Runs on」服务器提示；精简多处重复或多余的设置与空状态说明文案；通过历史/深链打开已删除的定位消息时静默回退到最新消息，不再报错。

## en

Brought the 1.30 web-parity polish bundle to mobile and desktop:

- **Runtime detection retry**: Computer details now show pending or incomplete runtime detection above the runtime chips; pressing Retry waits in one detecting state until the Computer reports a new result (or fails/times out), and runtime install/update hints now tell you to restart Raft Computer on that machine.
- **Runtime usage auto-check**: Runtime usage cards check automatically on open and on return to the app when due, show "Check again" only once the reading is at least two minutes old (or "Retry" on failure/timeout), show check outcomes inline on the updated line, and give back the 2-minute window on failed/offline/timed-out checks.
- **Agent workspaces refresh**: Replaced the Scan/Rescan button with a Refresh icon button on wide/desktop screens and pull-to-refresh on phones, deduplicated in-flight scans, kept session-scanned workspace lists when reopening a Computer, and added a warning banner with Retry above any kept list when a scan fails.
- **Slack channel picker auto-read**: Channel details' Slack channel menu now reads on open and on return to the app under a shared 1-minute floor, shows "Reading Slack channels…" at the top of the open menu while loading, and shows a warning banner with Retry if reading fails.
- **Icons, action cards, copy, and deleted-target opens**: Leave channel and Leave server now use the open-door icon (Sign out keeps log-out), action cards drop the redundant "Runs on <server>" notice, redundant settings and empty-state copy was trimmed across all 8 locales, and replayed opens pointing to a deleted message now open quietly on the latest messages.

## ja

1.30 の Web 側ポリッシュ改善と文言整理をモバイル・デスクトップに反映しました：

- **ランタイム検出の再試行**：Computer 詳細のランタイム一覧の上に検出中・未完了の状態を表示し、「再試行」を押すと新しい結果が届く（またはタイムアウト・失敗する）まで検出中を維持します。関連する案内文言もマシン上の Raft Computer を再起動する表現に更新しました。
- **ランタイム使用量の自動確認**：カードを開いたときやアプリ復帰時に必要な場合のみ自動確認し、「再確認」は更新から 2 分以上経過したときのみ表示します。タイムアウト・失敗・オフライン時は更新行に結果を表示し、クールダウンを返却します。
- **エージェントワークスペースの更新**：ワイド画面・デスクトップでは見出し右の更新アイコンボタン、モバイルではプルダウン更新に切り替え、重複スキャンを防止しました。セッション内で読み込んだ一覧は再表示時も保持され、失敗時は既存一覧の上に「再試行」付きバナーを表示します。
- **Slack チャンネル一覧の自動読み込み**：チャンネル詳細の Slack チャンネルメニューを開いたときやアプリ復帰時に 1 分間隔の共通下限内で自動読み込みを行い、読み込み中はメニュー先頭に状態を表示、失敗時は「再試行」付きバナーを表示します。
- **アイコン・アクションカード・文言・削除済みメッセージ遷移**：チャンネル退出とサーバー退出のアイコンを開いたドアに統一し、アクションカードの不要なサーバー実行表示と重複する説明文言を全 8 言語で整理しました。削除済みメッセージを指す再オープン時はエラーを出さず最新メッセージへ静かに遷移します。
