---
type: fixed
area: shell
platforms:
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4197
rollout: full
---

## zh-CN

Compose 版现在会把运行日志写到电脑上（macOS 为「~/Library/Logs/Raft」），每个文件最多 5 MB、最多保留 5 个，日志里不含消息正文和登录凭据。应用意外退出时，原因和出错位置会记在日志里，下次启动时也会记下上次没有正常退出。在「设置 › 关于 › 诊断」里可以查看最近的日志，点「导出日志」会在访达中打开日志文件夹，方便附在问题反馈里。同时减少了打开回复（话题）时的卡顿：同一个回复面板不再在每个保留的标签页里各加载一份，大图片按较小的尺寸解码，本地数据库不再每次读取都重新打开。

## en

The Compose app now writes its log to the computer (on macOS in "~/Library/Logs/Raft"), up to five files of 5 MB each, without message text or sign-in credentials. If the app quits unexpectedly, the reason and where it happened are recorded in the log, and the next start notes that the previous session did not end normally. Settings › About › Diagnostics shows the latest log lines, and Export logs opens the log folder in Finder so the files can be attached to a report. Opening a reply thread also stutters less: the reply panel is no longer loaded once for every kept tab, large images are decoded at a smaller size, and the local database is no longer reopened for every read.

## ja

Compose 版は動作ログをコンピューターに保存するようになりました（macOS では「~/Library/Logs/Raft」）。1 ファイル最大 5 MB、最大 5 ファイルまでで、メッセージ本文やサインイン情報は含みません。アプリが予期せず終了した場合は、その原因と発生箇所がログに記録され、次回の起動時には前回が正常に終了しなかったことも記録されます。「設定 › 情報 › 診断」で最新のログを確認でき、「ログをエクスポート」で Finder にログフォルダーが開くので、問題の報告にファイルを添付できます。あわせて、返信（スレッド）を開いたときのもたつきを減らしました。返信パネルを保持中のタブごとに読み込むことはなくなり、大きな画像は小さめのサイズでデコードし、ローカルデータベースを読み込みのたびに開き直すこともなくなりました。
