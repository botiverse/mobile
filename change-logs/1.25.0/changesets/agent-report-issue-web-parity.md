---
type: changed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

「报告问题」面板现在与网页版一致。Agent 没有分配到机器时，不再显示无法勾选的「运行时会话记录」选项；「报告」按钮不再带图标。提交失败时，提示会写明是哪一步失败（例如「上传报告包失败」）以及服务器给出的原因，下面附有可复制的 Trace ID，不再另外弹出提示。提交成功页的图标和按钮文字也与网页版一致。

## en

The Report issue panel now matches the web. When the agent isn't on a machine, the Runtime session transcript option no longer appears as a box you can't tick, and the Report button no longer has an icon. If submitting fails, the panel says which step failed (for example "Upload report bundle failed") and the reason the server gave, with a Trace ID you can copy under it, and no longer shows a separate pop-up message. The icon and button labels on the Report submitted screen also match the web.

## ja

「問題を報告」パネルがウェブ版と同じになりました。エージェントがマシンに割り当てられていない場合、チェックできない「ランタイムセッションのトランスクリプト」の項目は表示されなくなり、「報告」ボタンのアイコンもなくなりました。送信に失敗したときは、どの手順で失敗したか（例：「レポートバンドルのアップロードに失敗しました」）とサーバーが返した理由が表示され、その下にコピーできる Trace ID が表示されます。別のポップアップ通知は出なくなりました。送信完了画面のアイコンとボタンの表記もウェブ版と同じになりました。
