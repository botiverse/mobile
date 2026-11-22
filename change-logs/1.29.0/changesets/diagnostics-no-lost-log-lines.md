---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
  - desktop
pull_request: 5423
rollout: full
---

## zh-CN

应用同时写入多条日志时，诊断日志不再丢失其中的记录。在 Android 上，已经完成的请求也不会再因为结束记录丢失而在诊断中显示为卡住。

## en

Diagnostics no longer lose log lines when the app writes several at the same moment. On Android, a request that finished is no longer shown as stuck in diagnostics because its last line went missing.

## ja

アプリが同時に複数のログを書き込んでも、診断ログの記録が失われなくなりました。Android では、完了したリクエストが終了の記録の欠落によって、診断で止まったままと表示されることもなくなりました。
