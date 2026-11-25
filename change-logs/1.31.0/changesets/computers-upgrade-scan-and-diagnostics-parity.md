---
type: improved
area: computers
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

对齐 1.31.0 Web 端三项电脑与问题反馈体验：
- 电脑远程升级因磁盘空间不足失败时，直接展示空间不足说明（包含所需与剩余空间），不再显示无效的手动安装命令。
- 电脑工作区扫描支持在手动刷新时跳过缓存，并在扫描提前结束（`partial`）时将大小与文件数标注为「至少 …」。
- Agent「报告问题」在本人绑定的电脑上新增可选的「Computer 诊断包」及时间范围单选（本次报告前 1 小时 / 最近 24 小时 / 最近 7 天）和隐私说明；「我的反馈」支持与截图一同附加最多 1 个不超过 10 MiB 的诊断 `.zip` 压缩包。

## en

Brings 1.31.0 web parity for Computer upgrades, workspace scans, and diagnostics reporting:
- Computer upgrade failures caused by low disk space now show the dedicated out-of-space message (with needed and remaining space when available) without the manual install fallback command.
- Computer workspace scans now send `refresh=1` on explicit refresh to bypass the daemon cache, and prefix size and file counts with "at least …" when a bounded scan ends partially (`partial: true`).
- Agent "Report issue" on your own attached Computer now offers an opt-in Computer diagnostics bundle with a time-range selector (hour before this report, last 24 hours, last 7 days) and privacy disclosure; My Feedback now accepts up to one `.zip` diagnostics archive (up to 10 MiB, verified as a zip and uploaded as `application/zip`) alongside screenshots.

## ja

1.31.0 の Web 版に合わせて、コンピューターと問題報告の 3 点を更新しました：
- コンピューターのアップグレードがディスク容量不足で失敗した際、必要な空き容量と残り容量を含む専用メッセージを表示し、手動インストールコマンドを表示しないようになりました。
- コンピューターのワークスペーススキャンで明示的な更新時にキャッシュをバイパスし、制限により途中終了（`partial`）した行ではサイズとファイル数に「〜以上」を付けて表示するようになりました。
- 自分が接続したコンピューターのエージェント「問題を報告」に、オプトインの「Computer 診断パッケージ」と期間選択（直前 1 時間 / 過去 24 時間 / 過去 7 日間）および注意事項を追加しました。また「フィードバック」ではスクリーンショットに加えて最大 10 MiB の診断用 `.zip` ファイルを 1 点添付できるようになりました。
