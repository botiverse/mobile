---
type: improved
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: flag
---

## zh-CN

与网页版一致，Computer 页面的运行时卡片提供「升级」时，现在会说明 Computer 检查更新的结果，只在有新版本时提供「升级」：有新版本时显示「1.5.10 → 1.5.11 可更新」和「升级」；已是最新版本时显示「1.5.11 · 已是最新」，不再显示「升级」；更新由软件包管理器等方式管理时显示「更新在 Raft 之外管理」；无法检查时显示「无法检查更新」。命令行工具版本太旧、无法在这里登录的运行时仍会显示「升级」。需要 Computer 1.25.8 或更高版本；较旧的 Computer 与之前一样显示「升级」。

## en

As on the web, a runtime's card on the Computer page now says what the Computer's update check found and offers Upgrade only when a newer version exists: "1.5.10 → 1.5.11 available" with Upgrade, "1.5.11 · Latest" with no Upgrade when the runtime is already up to date, "Updates are managed outside Raft" when a package manager or similar handles its updates, and "Couldn't check for updates" when the check failed. A runtime whose command-line tool is too old to sign in from here keeps Upgrade. This needs Computer 1.25.8 or later; older Computers offer Upgrade as before.

## ja

ウェブ版と同じように、コンピューターのページのランタイムのカードに、コンピューターがアップデートを確認した結果が表示され、新しいバージョンがあるときだけ「アップグレード」が表示されるようになりました。新しいバージョンがあるときは「1.5.10 → 1.5.11 が利用可能」と「アップグレード」、すでに最新のときは「1.5.11 · 最新」と表示され「アップグレード」は表示されません。パッケージマネージャーなどがアップデートを管理しているときは「アップデートは Raft の外で管理されています」、確認できなかったときは「アップデートを確認できませんでした」と表示されます。コマンドラインツールが古くてここからログインできないランタイムには、引き続き「アップグレード」が表示されます。コンピューター 1.25.8 以降が必要です。それより前のコンピューターでは、これまでどおり「アップグレード」が表示されます。
