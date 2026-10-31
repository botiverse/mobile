---
type: changed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4029
rollout: full
---

## zh-CN

删除、移除、重置等需要确认的对话框，以及需要输入内容的对话框（如创建频道、创建 Agent、新建任务），点击对话框外面不会再关闭，避免误触丢掉已经输入的内容，和网页版一致。仍可用「取消」、× 或返回键关闭。

## en

Confirmation dialogs for actions such as delete, remove or reset, and dialogs where you type (such as creating a channel, an agent or a task), no longer close when you tap outside them, so a stray tap doesn't throw away what you entered. This matches the web. Cancel, × and Back still close them.

## ja

削除・除外・リセットなどの確認ダイアログや、入力が必要なダイアログ（チャンネル作成、エージェント作成、タスク作成など）は、外側をタップしても閉じなくなりました。誤タップで入力内容が失われません。Web 版と同じ動作です。「キャンセル」、×、戻るボタンでは引き続き閉じられます。
