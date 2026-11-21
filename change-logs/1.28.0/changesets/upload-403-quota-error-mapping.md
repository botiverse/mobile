---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: 5236
rollout: full
---

## zh-CN

当月度文件上传额度用完、频道因套餐限制处于只读状态或频道已归档时，上传附件不再误报为「你没有执行此操作的权限」，而是显示对应原因，并在额度用尽或频道被冻结时直接提示升级或额度说明。

## en

Uploading an attachment when the monthly file upload quota is used up, when a channel is read-only under plan limits, or when a channel is archived no longer shows a generic permission-denied error. The app now displays the actual reason and opens the matching upgrade or limit notice when applicable.

## ja

月間のファイルアップロード上限に達したときや、プラン制限でチャンネルが読み取り専用のとき、アーカイブ済みチャンネルで添付ファイルをアップロードしたときに、権限不足のエラーが誤表示されなくなりました。実際の理由が表示され、必要に応じてアップグレードや上限の案内が開きます。
