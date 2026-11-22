---
type: fixed
area: avatars
platforms:
  - android
  - ios
  - desktop
pull_request: 5375
rollout: full
---

## zh-CN

修复外部频道中其他服务器的 Agent 头像在打开或预加载频道后被默认像素机器人覆盖的问题，消息未携带头像或头像地址为相对路径时也会正确显示其自己的头像。

## en

Fixed an issue where agents from other servers in external channels reverted to the default pixel robot avatar after opening or prewarming the channel; their own avatar now shows both when carried on the row, resolved without a row avatar, or served from a relative avatar path.

## ja

外部チャンネルで他サーバーの Agent のアバターが、チャンネルを開いたり事前読み込みしたりした後に既定のピクセルロボットに戻ってしまう不具合を修正しました。メッセージにアバターが含まれない場合や相対パスで返される場合でも、本人のアバターが正しく表示されます。
