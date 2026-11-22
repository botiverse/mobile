---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 5310
rollout: full
---

## zh-CN

修复多处界面日期与时间显示不一致的问题：12/24 小时制设置切换现可实时同步到全局页面与弹窗；联合频道宽限期到期时间不再直接显示原始 ISO 字符串；邀请链接到期日和账单周期日期改按本地时区计算，避免跨午夜时出现 UTC 日期偏差；同时统一账号验证、微信绑定、通知暂停、反馈工单和紧凑日期等界面的时区与时间格式处理。

## en

Fixed inconsistent date and time formatting across the app: switching between 12-hour and 24-hour time now propagates reactively across pages and dialogs, joint-channel grace deadlines render as formatted local date/time instead of raw ISO strings, invite link expiration and billing cycle dates are calculated in the local timezone rather than slicing UTC dates, and account verification, WeChat connection, notification pause, feedback ticket, and compact date labels now consistently honor local timezone and 12h/24h preferences.

## ja

アプリ全体の日時表示の不整合を修正しました。設定での 12/24 時間表記の切り替えが各画面やダイアログにリアルタイムで反映されるようになり、ジョイントチャンネルの猶予期限が生の ISO 文字列ではなくローカル日時で表示されます。招待リンクの有効期限や請求サイクルの日付も UTC の切り出しではなくローカルタイムゾーンで計算され、アカウント認証・WeChat 連携・通知一時停止・フィードバックチケット・コンパクト日付表示もローカルタイムゾーンと 12/24 時間設定に従うようになりました。
