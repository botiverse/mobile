---
type: fixed
area: app-shell
platforms:
  - android
  - ios
  - desktop
pull_request: 3925
rollout: full
---

## zh-CN

在新版本之上安装旧版本 Raft（例如从内测版换回 TestFlight 版）后，启动时不再闪退，而是显示「请更新 Raft 后再打开本地数据」页面，说明本地数据来自更新的版本，更新 Raft 后可以重试；本地数据不会被改动。

## en

Installing an older Raft build over a newer one (for example going back from an alpha to the TestFlight build) no longer crashes at launch. The app shows "Update Raft to open this local data", explaining that your local data comes from a newer version and letting you retry after updating; your local data is left unchanged.

## ja

新しいバージョンの上に古い Raft をインストールした場合（たとえばアルファ版から TestFlight 版に戻した場合）でも、起動時にクラッシュしなくなりました。「ローカルデータを開くには Raft を更新してください」というページが表示され、ローカルデータが新しいバージョンのものであることを説明し、更新後に再試行できます。ローカルデータは変更されません。
