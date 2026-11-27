---
type: fixed
area: browser
platforms: [android, ios]
rollout: all
---

## zh-CN
修复并优化应用内浏览器关闭、三方 OAuth 重定向、链接加载与右侧浮窗体验：
- **关闭不再误拉起外部浏览器**：退出已授权应用页面时立即进入关闭态并移除 WebView 宿主，修复旧版 Android WebView（不支持 `MULTI_PROFILE`）在关闭收尾帧误触发系统浏览器兜底跳转的问题。
- **保留应用内 OAuth / SSO 重定向与防伪装顶栏**：跨源外跳隔离改为与桌面端一致的规则——仅在已提交文档位于应用来源且非服务端重定向时才拆出外链页；如 Stamp 点击「使用 GitHub 授权登录」触发的 `302` 重定向及后续授权回跳保留在同一 WebView 与 Cookie 环境中（同时自动剥离 `Raft-Host` 头），并在暂离应用来源期间将顶栏切换为仅展示当前真实域名。
- **拒绝登录关闭与未提交刷新恢复**：首屏尚未提交文档时关闭「已取消登录」提示会直接关闭页面，不再暴露空白白屏；未提交或停留在 `about:blank` 时点击刷新会重新加载目标地址。
- **默认关闭，手动浮窗**：应用内浏览器在首页点击左上角 `←`、系统返回或快速退出 `×` 时默认直接关闭页面，不再自动收进浮窗；需要后台保留时可在右上角 `⋮` 菜单中选择「浮窗」。
- **更稳的链接跳转与即时加载反馈**：等待鉴权快照完成水合后再校验账号/环境边界并在启动时预热已安装应用来源缓存，避免每次打开对话取消缓存请求导致点击链接静默卡顿；打开页面与页内点击链接时顶部进度条 0ms 即刻显示，首次绘制前展示居中加载指示。
- **右侧浮窗拖动跟手**：浮窗改用布局阶段直接读取实时软键盘与拖动偏移、固定 `pointerInput` 并隔离边缘返回手势，修复二次拖动回跳与输入法动画打断拖动的问题。

## en
Fixed in-app browser close behavior, OAuth redirect preservation, declined-sign-in recovery, instant loading feedback, installed-app link warm-up, and floating pill dragging:
- **Closing never launches the external browser**: closing an authorized Raft App page immediately unmounts the browser body and latches authorization state so teardown recomposition on pre-Chromium 119 Android WebViews (without `MULTI_PROFILE`) never triggers the fail-closed system-browser handoff.
- **In-app OAuth / SSO redirect parity & anti-spoofing top bar**: cross-origin outbound routing now matches Desktop `appTabLeaves` parity—server redirects and off-origin OAuth round trips (such as Stamp's GitHub OAuth `302` flow) stay inside the same WebView and cookie jar while stripping `Raft-Host`, and the top bar switches to domain-only display (`github.com`) until the callback returns to the App's origin.
- **Declined sign-in close & uncommitted reload recovery**: closing the declined-sign-in overlay before any document commits now closes the page cleanly instead of exposing a dead white WebView, and Reload falls back to loading the launch URL when uncommitted or on `about:blank`.
- **Default close, explicit manual float**: tapping top-left `←` at root, system Back at root, or quick-exit `×` now closes the page cleanly instead of auto-floating; use **Float window** in the `⋮` overflow menu when you want to park the page on the right-edge floating pill.
- **Instant loading feedback & reliable installed-app link opens**: guarded session-context resets until `authSnapshot` hydrates and warmed the installed-app origins cache at shell startup so opening threads no longer cancels cache requests; in-app browser pages now render a centered loading indicator before first visual commit and show the top progress bar with `0ms` delay on open and in-page link clicks.
- **Smooth floating pill drag**: moved pill positioning and live IME inset tracking into placement-only `.layout` with `.raftKeepsBackSwipe()` and a stable `.pointerInput(Unit)` so consecutive drags start from the live position without jumping back and survive IME frames cleanly.

## ja
アプリ内ブラウザの終了動作、OAuth リダイレクト保持、読み込みフィードバック、フローティングピルのドラッグ操作を修正・改善しました：
- **終了時に外部ブラウザを起動しない**：認証済みアプリ画面を閉じる際に即座にブラウザ本体をアンマウントし、終了フレームの再コンポーズで `MULTI_PROFILE` 非対応の Android WebView が誤って外部ブラウザへ遷移する問題を修正しました。
- **アプリ内 OAuth / SSO リダイレクトの保持となりすまし防止トップバー**：クロスオリジン外遷移の分離条件をデスクトップ版と統一し、サーバーリダイレクトおよびアプリオリジン外での認証往復（Stamp の GitHub 認証など）は `Raft-Host` ヘッダーを除去した上で同一 WebView と Cookie を維持します。別オリジン滞在中はトップバーをドメイン単独表示へ切り替えます。
- **ログイン拒否後の終了と未コミット時の再読み込み改善**：ドキュメント未コミット時にログイン拒否オーバーレイを閉じた場合はそのまま画面を閉じて白画面残留を防ぎ、未コミットや `about:blank` での再読み込み時は起動 URL を再ロードするようにしました。
- **デフォルトで終了、手動でフロート表示**：先頭ページでの左上 `←`・システム戻る・クイック終了 `×` は既定でページを閉じる動作に統一し、背面に保持したい場合は右上 `⋮` メニューの「フロート表示」から手動で切り替えられるようにしました。
- **即時の読み込みフィードバックとリンク起動の安定化**：認証スナップショットの初期化完了前にキャッシュやタブがクリアされないよう保護し、シェル起動時にインストール済みアプリ一覧をプリウォーム。初回表示前の読み込みインジケーターと 0ms 遅延のプログレスバーにより、起動時・ページ内リンク遷移時の応答性を高めました。
- **右端フローティングピルの滑らかなドラッグ**：配置と IME インセット追従をレイアウト配置フェーズのみに移行し、エッジ戻るジェスチャーとの競合や連続ドラッグ時の位置戻りを解消しました。
