---
type: fixed
area: updates
platforms:
  - android
pull_request: 0
rollout: full
---

## zh-CN

（Alpha 测试版）应用内更新已下载、但 Raft 还不能安装应用时，更新会显示“需要权限”；点“安装”会先说明原因，再打开 Raft 的“安装未知应用”设置。允许后回到 Raft，安装程序会直接打开，无需重新下载更新。（内部诊断）Android 上的原生崩溃报告会注明发生崩溃的版本。Hands Android SDK 升级到 0.14.3。

## en

(Alpha) When an in-app update has downloaded but Raft is not yet allowed to install apps, the update now shows that a permission is required, and Install explains why before opening Raft's "Install unknown apps" setting. Once you allow it and come back to Raft, the installer opens without downloading the update again. (Internal diagnostics) Native crash reports on Android now name the version that crashed. The Hands Android SDK is updated to 0.14.3.

## ja

（Alpha 版）アプリ内アップデートのダウンロードが済んでいても Raft にアプリのインストールが許可されていない場合、アップデートに権限が必要であることが表示され、「インストール」をタップすると理由を説明してから Raft の「不明なアプリのインストール」の設定を開きます。許可して Raft に戻ると、アップデートを再ダウンロードせずにインストーラーが開きます。（内部診断）Android のネイティブクラッシュのレポートに、クラッシュしたバージョンが記載されるようになりました。Hands Android SDK を 0.14.3 に更新しました。
