---
type: fixed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 4833
rollout: full
---

## zh-CN

在「计算机」页面退出运行时登录、使用额度重置（Codex 和 Claude），以及查看 Claude 可用的额度重置时，如果计算机需要超过 30 秒才能完成，App 现在会一直等到结果出来，不会再在 30 秒时中断并提示结果未知。与网页版一致，按钮在等待期间保持加载状态；离开页面就停止等待。这些操作失败时，提示下方会显示 Trace ID，可以复制后发给我们。

## en

Signing a runtime out, using a limit reset (Codex and Claude) and viewing Claude's available resets from a Computer's page now wait for the result when the Computer takes longer than 30 seconds, instead of stopping at 30 seconds and saying the outcome is unknown. As on the web, the button keeps loading while it waits, and leaving the page stops the wait. When one of these fails, the message now shows its Trace ID, which you can copy and send to us.

## ja

コンピューターのページでランタイムのサインアウト、上限リセットの使用（Codex と Claude）、Claude で使える上限リセットの確認を行うとき、コンピューターの処理に 30 秒以上かかっても、結果が出るまで待つようになりました。これまでは 30 秒で止まり、結果が不明と表示されていました。Web 版と同じく、待っている間はボタンが読み込み中のままになり、ページを離れると待機を止めます。これらが失敗したときは、メッセージの下に Trace ID が表示され、コピーして送っていただけます。
