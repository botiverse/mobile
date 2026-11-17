---
type: fixed
area: diagnostics
platforms:
  - android
pull_request: null
rollout: full
---

## zh-CN

（内部诊断）Android 上因原生崩溃退出的情况，不再默认视为已由崩溃上报组件提交：只有确认对应工单已被接收时才跳过，否则由应用自行上报，并带上进程号和退出时间，便于与原生崩溃工单去重。WebView 渲染进程崩溃的报告只在提交成功后才计入每日上限，提交失败会退还额度并最多重试一次。

## en

(Internal diagnostics) On Android, an exit caused by a native crash is no longer assumed to have been filed by the crash reporter: it is skipped only when that ticket is confirmed received, and otherwise the app reports it itself, with the process id and exit time needed to match it to the native crash ticket. WebView renderer crash reports now count against their daily limit only after they are sent successfully; a failed send gives the slot back and is retried at most once.

## ja

（内部診断）Android でネイティブクラッシュによる終了は、クラッシュレポーターが送信済みとはみなさなくなりました。該当チケットの受理が確認できた場合のみ省略し、それ以外はアプリが自ら報告します。報告にはネイティブクラッシュのチケットと照合するためのプロセス ID と終了時刻が含まれます。WebView のレンダラークラッシュの報告は、送信に成功した場合のみ 1 日の上限に数えられ、送信に失敗した場合は枠が戻り、再試行は最大 1 回です。
