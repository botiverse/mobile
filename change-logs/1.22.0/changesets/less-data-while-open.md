---
type: improved
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

应用在前台打开时使用的流量和电量大大减少。之前在忙碌的服务器上，即使你不在首页或动态页面，应用也会每隔几秒重新下载整个首页和动态列表；现在它只在你回到这些页面时更新一次，并把同时发出的相同请求合并为一次。

## en

Raft uses much less data and battery while it is open. On a busy server the app used to download Home and the Activity list again every few seconds, even while you were looking at something else; now it catches up once when you go back to them, and identical requests made at the same moment are sent only once.

## ja

Raft を開いている間のデータ通信量とバッテリー消費が大幅に減りました。これまでは活発なサーバーで、ホームやアクティビティを見ていないときでも数秒ごとにホームとアクティビティ一覧を再取得していました。今後はそれらの画面に戻ったときに一度だけ更新し、同時に出された同じリクエストは 1 回にまとめて送信します。
