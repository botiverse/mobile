---
type: fixed
area: diagnostics
platforms:
  - android
pull_request: null
rollout: full
---

## zh-CN

（内部诊断）Android 上因原生崩溃退出的情况，不再默认视为已由崩溃上报组件提交，而是由应用自行上报，并与崩溃上报组件的原生崩溃工单使用同一个提交 ID，服务器会把两者合并为一个工单，不再产生重复工单。较早版本记录的进程没有该 ID，仍按原方式处理：只有确认对应工单已被接收时才跳过，否则单独上报。WebView 渲染进程崩溃的报告只在提交成功后才计入每日上限，提交失败会退还额度并最多重试一次。Hands Android SDK 升级到 0.13.2。

## en

(Internal diagnostics) On Android, an exit caused by a native crash is no longer assumed to have been filed by the crash reporter: the app reports it itself, under the same submission id as the crash reporter's native crash ticket, and the server merges the two into one ticket instead of filing a duplicate. Processes recorded by an older build have no such id and keep the previous handling: skipped only when the native ticket is confirmed received, otherwise reported on their own. WebView renderer crash reports now count against their daily limit only after they are sent successfully; a failed send gives the slot back and is retried at most once. The Hands Android SDK is updated to 0.13.2.

## ja

（内部診断）Android でネイティブクラッシュによる終了は、クラッシュレポーターが送信済みとはみなさなくなりました。アプリが自ら報告し、クラッシュレポーターのネイティブクラッシュのチケットと同じ送信 ID を使うため、サーバーが両者を 1 つのチケットに統合し、重複チケットは作られません。以前のビルドで記録されたプロセスにはこの ID がないため、従来どおり、該当チケットの受理が確認できた場合のみ省略し、それ以外は単独で報告します。WebView のレンダラークラッシュの報告は、送信に成功した場合のみ 1 日の上限に数えられ、送信に失敗した場合は枠が戻り、再試行は最大 1 回です。Hands Android SDK を 0.13.2 に更新しました。
