---
type: changed
area: settings
platforms:
  - android
  - ios
pull_request: null
rollout: full
---

## zh-CN

在「设置 › 关于」里复制环境信息、在「诊断」里分享环境信息时，只会带上环境摘要：版本、构建号、源码提交、平台与系统版本、设备、所选服务器和连接状态，不再附带应用运行日志，复制或分享也不会上传任何内容。反馈提交失败时复制的诊断信息同样只有这份摘要。需要日志时，请在「反馈」里提交，提交的反馈会照常附上日志。

## en

Copying environment info in Settings › About, or sharing it from Diagnostics, now gives only an environment summary: version, build, source commit, platform and OS version, device, selected server and connection status. App logs are no longer included, and copying or sharing uploads nothing. The diagnostics copied when a report fails to send are the same summary. To include logs, send them with Feedback, which still attaches them.

## ja

「設定 › 情報」で環境情報をコピーしたとき、または「診断」から共有したときは、環境の概要だけになりました：バージョン、ビルド、ソースのコミット、プラットフォームと OS のバージョン、デバイス、選択中のサーバー、接続状態です。アプリのログは含まれなくなり、コピーや共有で何かがアップロードされることもありません。レポートの送信に失敗したときにコピーされる診断情報も同じ概要です。ログが必要なときは「フィードバック」から送信してください。フィードバックには引き続きログが添付されます。
