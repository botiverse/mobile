---
type: fixed
area: errors
platforms: [android, ios, desktop]
rollout: full
pull_request: 5497
---

## zh-CN
- 修复操作失败提示（包括打开私信、加入频道、上传附件配额检查、发送文件评论与服务器邀请加入页）只显示泛化失败文案或暴露原始错误码的问题，统一展示服务端具体拒绝原因与许可证/席位上限提示。

## en
- Fixed failure toasts (including opening direct messages, joining channels, attachment upload limit checks, and posting file comments) and the server join invite page showing only a generic error or raw error code, so specific server refusal reasons and license or seat limit messages display clearly.

## ja
- ダイレクトメッセージを開く、チャンネルに参加する、添付ファイル上限の確認、ファイルコメントの送信、サーバー招待ページなどで失敗した際に、汎用メッセージや生のエラーコードのみが表示される不具合を修正し、サーバーの具体的な拒否理由やライセンス・シート上限を分かりやすく表示するようにしました。
