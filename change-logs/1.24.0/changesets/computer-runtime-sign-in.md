---
type: added
area: computers
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

Computer 页面的运行时卡片现在可以像网页版一样登录和退出该 Computer 上的运行时：Claude Code、Codex CLI、Kimi Code（先选择账号所在地区）、Qoder、Grok Build，以及服务器开放时的 Antigravity。登录在 Computer 上进行：卡片会显示要在浏览器中打开的登录页面（Claude Code 和 Antigravity 还需粘贴页面给出的代码），或一个可复制的设备码。Pi 可以按模型提供方逐个登录账号或保存 API Key，也可以逐个退出或移除。未登录的运行时标签会变成橙色并注明“（未登录）”；Computer 上缺少或版本过旧的命令行工具会说明原因，并给出可复制的安装命令。只有连接这台 Computer 的人或服务器所有者、管理员可以操作。出错时会显示 Trace ID。

## en

Runtime cards on the Computer page can now sign the Computer's runtimes in and out, as on the web: Claude Code, Codex CLI, Kimi Code (after choosing the account's region), Qoder, Grok Build, and Antigravity where the server offers it. The sign-in runs on the Computer: the card shows the sign-in page to open in your browser (for Claude Code and Antigravity, then paste the code it shows) or a device code to copy. Pi signs in per model provider, with an account or an API key, and each provider can be signed out or removed on its own. A signed-out runtime's badge turns orange and says "(not signed in)"; when the runtime's command-line tool is missing or too old on the Computer, the card says so and gives the install command to copy. Only the person who connected the Computer, or a server owner or admin, can do this. Errors show their Trace ID.

## ja

コンピューターページのランタイムカードから、Web 版と同じようにコンピューター上のランタイムへのログインとログアウトができるようになりました：Claude Code、Codex CLI、Kimi Code（先にアカウントの地域を選択）、Qoder、Grok Build、そしてサーバーが提供している場合は Antigravity。ログインはコンピューター上で行われます。カードにはブラウザーで開くログインページ（Claude Code と Antigravity は表示されたコードを貼り付け）か、コピーできるデバイスコードが表示されます。Pi はモデルプロバイダーごとにアカウントでログインするか API キーを保存でき、プロバイダーごとにログアウトや削除もできます。未ログインのランタイムのバッジはオレンジになり「（未ログイン）」と表示されます。コンピューターにコマンドラインツールがない、または古い場合は理由とコピーできるインストールコマンドが表示されます。操作できるのは、このコンピューターを接続した人、またはサーバーのオーナーか管理者だけです。エラーには Trace ID が表示されます。
