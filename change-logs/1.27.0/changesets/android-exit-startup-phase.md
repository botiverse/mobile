---
type: improved
area: diagnostics
platforms:
  - android
pull_request: 5099
rollout: full
---

## zh-CN

（诊断）Android 上 App 被系统结束后，下次启动发出的报告会写明那个进程启动到了哪一步（创建应用、打开第一个页面、画出第一帧），从启动到被结束隔了多久，以及距离上次安装更新多久，便于区分“启动时被拦”和“使用中被清理”。这些记录只保存在本机，并且和其他崩溃报告一样遵守隐私模式。

## en

(Diagnostics) After Android ends the app, the report sent at the next launch now says how far that process got through startup (app created, first screen opened, first frame drawn), how long after it started it was ended, and how long after the last update. This tells a kill during startup apart from one while the app was in use. The records stay on the device and follow privacy mode like other crash reports.

## ja

（診断）Android がアプリを終了した後、次回起動時に送られるレポートに、そのプロセスが起動のどこまで進んでいたか（アプリ作成、最初の画面、最初のフレーム描画）、起動から終了までの時間、前回のアップデートからの時間が含まれるようになりました。起動中の終了と使用中の終了を区別できます。記録は端末内にのみ保存され、ほかのクラッシュレポートと同じくプライバシーモードに従います。
