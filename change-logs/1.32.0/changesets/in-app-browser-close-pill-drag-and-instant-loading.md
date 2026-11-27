---
type: fixed
area: browser
platforms: [android, ios]
rollout: all
---

## zh-CN
修复并优化应用内浏览器关闭、链接加载与右侧浮窗体验：
- **关闭不再误拉起外部浏览器**：退出已授权应用页面时立即进入关闭态并移除 WebView 宿主，修复旧版 Android WebView（不支持 `MULTI_PROFILE`）在关闭收尾帧误触发系统浏览器兜底跳转的问题。
- **默认关闭，手动浮窗**：应用内浏览器在首页点击左上角 `←`、系统返回或快速退出 `×` 时默认直接关闭页面，不再自动收进浮窗；需要后台保留时可在右上角 `⋮` 菜单中选择「浮窗」。
- **更稳的链接跳转与即时加载反馈**：等待鉴权快照完成水合后再校验账号/环境边界并在启动时预热已安装应用来源缓存，避免每次打开对话取消缓存请求导致点击链接静默卡顿；打开页面与页内点击链接时顶部进度条 0ms 即刻显示，首次绘制前展示居中加载指示。
- **右侧浮窗拖动跟手**：浮窗改用布局阶段直接读取实时软键盘与拖动偏移、固定 `pointerInput` 并隔离边缘返回手势，修复二次拖动回跳与输入法动画打断拖动的问题。

## en
Fixed in-app browser close behavior, instant loading feedback, installed-app link warm-up, and floating pill dragging:
- **Closing never launches the external browser**: closing an authorized Raft App page immediately unmounts the browser body and latches authorization state so teardown recomposition on pre-Chromium 119 Android WebViews (without `MULTI_PROFILE`) never triggers the fail-closed system-browser handoff.
- **Default close, explicit manual float**: tapping top-left `←` at root, system Back at root, or quick-exit `×` now closes the page cleanly instead of auto-floating; use **Float window** in the `⋮` overflow menu when you want to park the page on the right-edge floating pill.
- **Instant loading feedback & reliable installed-app link opens**: guarded session-context resets until `authSnapshot` hydrates and warmed the installed-app origins cache at shell startup so opening threads no longer cancels cache requests; in-app browser pages now render a centered loading indicator before first visual commit and show the top progress bar with `0ms` delay on open and in-page link clicks.
- **Smooth floating pill drag**: moved pill positioning and live IME inset tracking into placement-only `.layout` with `.raftKeepsBackSwipe()` and a stable `.pointerInput(Unit)` so consecutive drags start from the live position without jumping back and survive IME frames cleanly.

## ja
アプリ内ブラウザの終了動作、リンク遷移の即時フィードバック、フローティングピルのドラッグ操作を修正・改善しました：
- **終了時に外部ブラウザを起動しない**：認証済みアプリ画面を閉じる際に即座にブラウザ本体をアンマウントし、終了フレームの再コンポーズで `MULTI_PROFILE` 非対応の Android WebView が誤って外部ブラウザへ遷移する問題を修正しました。
- **デフォルトで終了、手動でフロート表示**：先頭ページでの左上 `←`・システム戻る・クイック終了 `×` は既定でページを閉じる動作に統一し、背面に保持したい場合は右上 `⋮` メニューの「フロート表示」から手動で切り替えられるようにしました。
- **即時の読み込みフィードバックとリンク起動の安定化**：認証スナップショットの初期化完了前にキャッシュやタブがクリアされないよう保護し、シェル起動時にインストール済みアプリ一覧をプリウォーム。初回表示前の読み込みインジケーターと 0ms 遅延のプログレスバーにより、起動時・ページ内リンク遷移時の応答性を高めました。
- **右端フローティングピルの滑らかなドラッグ**：配置と IME インセット追従をレイアウト配置フェーズのみに移行し、エッジ戻るジェスチャーとの競合や連続ドラッグ時の位置戻りを解消しました。
