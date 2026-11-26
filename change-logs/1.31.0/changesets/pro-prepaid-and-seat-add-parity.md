---
type: added
area: billing
platforms: [android, ios, desktop]
---

## zh-CN
补齐 Prepaid Pro（`pro_prepaid`）与 Pro 席位扩展（`pro_seat_add`）原生端能力：
- iOS 只读「套餐与账单」页面支持展示 Prepaid Pro 的席位与到期日期、最后 7 天到期提醒及宽限期横幅与状态徽章，且严格遵守 App Store 3.1.1（不展示支付宝、续费或外链购买入口）。
- 接入 `pro_seat_add` 服务端开关与通知中心席位不足加入受阻卡片（`GET /api/servers/:id/seat-join-blocks` 与 `seat-join-blocks:changed` 实时推送）：Android/Desktop 所有者可一键前往账单加席位，非所有者可原生发起「向所有者请求加席位」；iOS 仅展示中性提示并保留关闭能力。
- Android/Desktop 新增原生「向所有者请求加席位」弹窗（`POST /api/actions/seat-requests`），支持选择所有者或由隐藏花名册服务器自动指派、填写可选原因，并在发送后提供跳转对应私信会话的提示。

## en
Added native mobile parity for Prepaid Pro (`pro_prepaid`) and Pro seat add (`pro_seat_add`):
- iOS view-only Plan & Billing now parses and renders Prepaid Pro seat summary, end date, 7-day ending-soon banner/badge, and grace-period warning banner/badge without Stripe billing details, Alipay copy, or external purchase/renew actions (App Store Guideline 3.1.1 compliant).
- Registered the per-server `pro_seat_add` flag and wired Notification Center `seat-join-blocks` cards (`GET /api/servers/:id/seat-join-blocks` + `seat-join-blocks:changed` realtime pings): owners on Android/Desktop open Plan & Billing to add seats, non-owners open the native seat-request dialog, and iOS shows a neutral seat-capacity notice with dismiss only.
- Added the native Android/Desktop non-owner "Ask an owner for seats" dialog (`POST /api/actions/seat-requests`) from both seat-join-blocked notices and Pro seat-limit dialogs when `pro_seat_add` is enabled, supporting owner selection, hidden-roster server assignment, optional reason, and DM navigation after sending.

## ja
Prepaid Pro（`pro_prepaid`）および Pro シート追加（`pro_seat_add`）のネイティブモバイル対応を追加しました：
- iOS の閲覧専用「プランと請求」画面で Prepaid Pro のシート数・終了日・終了 7 日前の通知バナー／バッジおよび猶予期間の警告バナー／バッジを表示するようにしました（App Store ガイドライン 3.1.1 に準拠し、Alipay や更新・購入導線は表示しません）。
- サーバー単位の `pro_seat_add` フラグと通知センターの参加ブロック通知（`GET /api/servers/:id/seat-join-blocks` および `seat-join-blocks:changed` リアルタイム通知）を接続しました。Android／デスクトップではオーナーは請求画面からシート追加、非オーナーはオーナーへの依頼ダイアログを開け、iOS では中立的なメッセージと非表示操作のみを表示します。
- Android／デスクトップ向けに非オーナー用の「オーナーにシートを依頼」ダイアログ（`POST /api/actions/seat-requests`）を追加し、オーナー選択・非公開名簿での自動割り当て・任意の理由入力・送信後の DM 遷移に対応しました。
