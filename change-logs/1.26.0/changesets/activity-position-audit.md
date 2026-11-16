---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 4863
rollout: flag
---

## zh-CN

即使动态不在屏幕上，动态角标也会保持准确：回到应用时，动态会重新读取自己的列表，之后的新消息在这个最新数字上累加，角标不会再跳回旧的数字。动态还不再每分钟整体重读一次，改为抽样核对单个会话。

## en

The Activity badge stays right even when Activity isn't on screen. When you come back to the app, Activity reads its list again and new messages add onto that fresh number, so the badge no longer falls back to an older count. Activity also stops re-reading the whole list every minute and spot-checks single conversations instead.

## ja

アクティビティが画面に出ていなくても、アクティビティのバッジが正確に保たれます。アプリに戻るとアクティビティがリストを読み直し、その後の新しいメッセージはその最新の数に加算されるため、バッジが古い数に戻らなくなりました。また、アクティビティはリスト全体を 1 分ごとに読み直すのをやめ、個々の会話を抜き取りで確認するようになりました。
