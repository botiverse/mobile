---
type: changed
area: billing
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
为 AppSumo 终身方案接入持久存储用量展示与存储上限弹窗：设置 › 套餐与账单中的用量条改为「存储空间」并按已用加预留字节计算（支持超出新档位上限时如实展示，不再显示按月重置日期）；触发上传存储上限时，弹窗展示「AppSumo 存储空间上限」及清理未使用文件指引，所有者可直达套餐与账单，普通成员显示关闭按钮。

## en
Added AppSumo retained storage UI in Settings › Plan & billing and the `AppSumoUploadLimit` dialog (`slock #17191`): AppSumo servers now label the usage meter "Storage", compute usage from `usedBytes + reservedBytes` without clamping or showing a monthly reset date, and show the AppSumo storage limit dialog with delete-unused-files guidance ("Manage AppSumo plan" for owners, "Close" for members) instead of Free-plan upgrade or monthly reset copy.

## ja
AppSumo 永久ライセンス向けの保持ストレージ表示とストレージ上限ダイアログに対応しました。設定 › プランと請求の使用量メーターを「ストレージ」に変更し、`usedBytes + reservedBytes` から使用量を算出して月間リセット日を非表示にしました。アップロード上限到達時は、無料プラン向けアップグレード文言ではなく「AppSumo のストレージ上限」と不要ファイル削除の案内を表示し、所有者には「AppSumo プランを管理」、メンバーには「閉じる」を表示します。
