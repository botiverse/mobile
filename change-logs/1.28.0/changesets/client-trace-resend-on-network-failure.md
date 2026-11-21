---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
  - desktop
pull_request: 5191
rollout: full
---

## zh-CN

（内部诊断）诊断事件发送时如果网络不通或服务器暂时出错，App 不再直接丢弃这些事件，而是保留在内存中稍后重发（间隔逐步加长，最多保留 30 分钟、500 条）。此前因断网而失败的附件上传，其诊断事件也会一并丢失，导致失败率被低估。隐私模式下的行为不变：开启时不发送也不保留。

## en

(Internal diagnostics) When diagnostic events cannot be sent because the network is down or the server has a temporary error, the app no longer discards them: it keeps them in memory and sends them again later (with growing intervals, for at most 30 minutes and 500 events). Before, the diagnostic event of an attachment upload that failed because the network dropped was lost with it, so failures were under-counted. Privacy mode behaves as before: when it is on, nothing is sent or kept.

## ja

（内部診断）ネットワークが切れている、またはサーバーが一時的にエラーを返すために診断イベントを送信できない場合、アプリはイベントを破棄せず、メモリに保持して後で再送します（間隔を徐々に延ばし、最長 30 分・最大 500 件）。これまでは、ネットワーク切断で失敗した添付ファイルのアップロードの診断イベントも一緒に失われ、失敗が少なく数えられていました。プライバシーモードの動作は変わりません。オンのときは送信も保持もしません。
