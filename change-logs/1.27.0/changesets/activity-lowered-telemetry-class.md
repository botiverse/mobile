---
type: improved
area: diagnostics
platforms:
  - android
  - ios
  - desktop
pull_request: 5111
rollout: full
---

## zh-CN

动态列表「未读数被调低」的诊断数据和网页对齐：标注是预期内、等待已读确认还是无法解释；同一次调低 90 秒内只上报一次；已读确认早于等待开始时也能正确认定。另外，动态行的就地核对会记录这个对话当时是否正打开在屏幕上。不影响界面。

## en

Activity's lowered-unread diagnostics now match the web: each report says whether it was expected, waiting on a read ack, or unexplained; the same lowering is reported once per 90 s; and a read ack that arrived before the wait began is recognised. The in-place check also records whether the conversation was open on screen. Nothing visible changes.

## ja

アクティビティの「未読数の引き下げ」の診断データを Web に合わせました。想定内か、既読確認待ちか、説明できないものかを記録し、同じ引き下げは 90 秒に 1 回だけ送信し、待機開始より前に届いた既読確認も正しく扱います。その場での照合では、その会話が画面に開かれていたかも記録します。表示は変わりません。
