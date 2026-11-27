---
type: fixed
area: tasks
platforms: [ios]
rollout: full
---

## zh-CN
修复 iPhone 开启「iOS 原生导航」时从频道或私聊进入「任务」或「文件」浮层后无法返回消息页的问题：浮层子标题不再抢占或清空频道原生导航栏（顶部 `⋯` 中的任务/文件开关保持可用），点击原生返回按钮与边缘右滑手势现在都会先关闭浮层回到消息列表，再退出频道。

## en
Fixed an issue where opening Tasks or Files from a channel or DM on iPhone with iOS native navigation enabled could trap the user on the Tasks/Files cover: the phone cover no longer hijacks or clears the conversation's native navigation bar (keeping Search and the `⋯` menu toggles visible), and both the native Back button and edge back swipe now close the cover back to messages before popping the conversation.

## ja
iPhone で「iOS ネイティブナビゲーション」を有効にしている際、チャンネルや DM からタスク／ファイルを開くとメッセージ画面へ戻れなくなる不具合を修正しました。カバー内のサブヘッダーが会話のネイティブナビゲーションバーを上書きしないようにし（`⋯` メニューの切替を保持）、ネイティブの戻るボタンおよび画面端スワイプの両方で先にカバーを閉じてメッセージへ戻れるようにしました。
