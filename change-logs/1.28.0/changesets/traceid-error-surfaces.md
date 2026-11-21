---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

补齐多处服务端报错的追踪 ID 展示与点击复制能力：会话加载失败/无权限页、频道文件页、首页/成员页错误卡、附件评论面板以及消息操作、任务更新、联合邀请、成员与设备管理的失败提示，现在都会在服务端返回追踪号时附带可复制的追踪 ID。

## en

Expanded consistent trace ID display and tap-to-copy across error surfaces: conversation load and permission error views, channel files, home and members error cards, attachment comments, and failure toasts for thread actions, task updates, joint invites, member actions, and computer settings now show a copyable trace ID whenever the server returns one.

## ja

複数のエラー画面と失敗トーストでトレース ID の表示とタップコピーを統一しました。会話の読み込みエラー／権限エラー、ファイル一覧、ホーム／メンバーのエラー表示、添付ファイルコメント、およびメッセージ操作・タスク更新・ジョイント招待・メンバー／端末管理の失敗トーストで、サーバーから返されたトレース ID を確認・コピーできるようになりました。
