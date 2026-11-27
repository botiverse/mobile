---
type: improved
area: navigation
platforms: [android, ios]
rollout: flag
---

## zh-CN
优化了手机端自定义底栏、服务器面板与语音听写体验：
- 安卓与 iOS 底部导航栏均默认展示 5 个标签（4 个页面 + 服务器）；安卓端支持在「编辑导航栏」中按需手动添加一个页面扩展到 6 个，并支持随时移回 5 个或重置恢复默认，数量偏好按账号和服务器持久化保存。
- 服务器面板里的「最近使用」列表现已按账号和服务器本地持久化，重启应用后不再清空，退出或失去服务器权限时自动清理。
- 手机端服务器面板顶部的服务器切换列表默认限制在约 5 行内滚动展示，避免服务器较多时铺满全屏挤压下方的「最近使用」和「更多」区域。
- 修复了语音听写激活态麦克风图标缩小的问题（保持与空闲态一致的大小与线条粗细，激活态底圆相应放大至 28 dp），并将听写按钮释放后的防误触屏蔽窗口对齐为 200 ms。

## en
Polished phone navigation customization, the server popover, and voice dictation controls:
- Both Android and iOS now default to 5 bottom tabs (4 places + Server); Android users can manually add a 5th place tab (6 total) in Edit navigation bar, remove it back to 5, or reset to default, persisted locally per account and server.
- Persisted the phone server popover's "Recently used" Artifacts and Raft Apps list across app restarts per account and server (cleaned up when server access is lost).
- Capped the top server switcher list inside the phone server popover to ~5 visible scrollable rows so long server lists no longer stretch full-screen over "Recently used" and "More".
- Fixed the active voice dictation microphone icon shrinking relative to its idle size (keeping the full glyph size inside a 28 dp active disc) and aligned the post-release click suppression guard to 200 ms.

## ja
モバイル版ボトムタブのカスタマイズ、サーバーパネル、および音声入力ボタンの体験を改善しました：
- Android / iOS ともにボトムタブの初期表示を 5 つ（4 ページ + サーバー）に統一しました。Android では「ナビゲーションバーを編集」から手動で 5 つ目のページを追加（合計 6 タブ）でき、いつでも 5 タブに戻したりリセットしたりでき、設定はアカウント・サーバー単位でローカルに永続化されます。
- サーバーパネルの「最近使った項目」をアカウント・サーバーごとにローカル保存し、アプリ再起動後も保持されるようにしました（サーバー権限喪失時に自動削除）。
- サーバーパネル上部のサーバー切替リストの表示高さを約 5 行分に抑えて内部スクロールさせ、サーバー数が多い場合でも画面全体を占有しないようにしました。
- 音声入力のアクティブ時にマイクアイコンが縮小する問題を修正（待機時と同じアイコンサイズを維持し、背景円を 28 dp に拡大）し、リリース後の誤クリック抑制ウィンドウを 200 ms に揃えました。
