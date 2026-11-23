---
type: fixed
area: profile
platforms:
  - android
  - ios
  - desktop
pull_request: 5505
rollout: full
---

## zh-CN

修复 Agent 资料页「近期动态」的显示与网页不一致的问题：按账号设置的偏好时区（含跨夏令时偏移）显示时间，避免与设备时区相差一小时；非当天记录改为本地化短日期格式（如「11月21日 11:23」）；「未发送消息」等状态标签固定独立一行显示在动作说明下方。

## en

Fixed Agent profile Recent activity parity with web: timestamps now follow the account's preferred time zone (including per-instant DST offsets) instead of drifting by an hour against the device time zone, non-today turns use the localized short date-time format (e.g., "Nov 21, 11:23"), and status chips such as "No message sent" sit on their own line below the turn summary.

## ja

エージェントのプロフィールにある「最近のアクティビティ」をウェブ版に合わせました。アカウントの優先タイムゾーン（サマータイムを含む）で時刻を表示して端末タイムゾーンとの 1 時間のずれを解消し、当日以外のターンはローカライズされた短い日時形式（例：「11月21日 11:23」）で表示し、「メッセージ未送信」などのチップを概要の下の独立した行に配置します。
