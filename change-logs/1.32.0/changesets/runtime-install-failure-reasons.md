---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
在 Computer 页面安装运行时时，请求现在会同时携带 `failureReason: true` 以接收 OAR 0.138 的结构化失败原因；安装失败后将按具体原因或退出码展示对应的多语言说明（下载失败、非安装脚本、超时、无退出码、退出码、安装后未找到、安装后不可用），并在输出末尾下方保留本次确认过的手动安装命令与官方文档链接，方便直接在目标 Computer 上排查和手动安装。

## en
Runtime install requests on the Computer page now opt into OAR 0.138 structured failure reasons (`failureReason: true`). Failed installs explain why they failed across all 8 locales (download failed, non-script response, timed out, stopped/no exit code, exit code, not found after install, or unusable after install) and display the confirmed manual install commands and vendor documentation link below any trailing output.

## ja
Computer ページからのランタイムインストール要求で `failureReason: true` を送信し、OAR 0.138 の構造化された失敗理由を受け取れるようになりました。インストールが失敗した場合は、理由または終了コードに応じた詳細メッセージ（ダウンロード失敗、スクリプト以外の応答、タイムアウト、終了コードなし、エラーコード、インストール後に見つからない、使用不可）を表示し、出力の末尾の下に確認済みの手動インストール手順と公式ドキュメントリンクを表示します。
