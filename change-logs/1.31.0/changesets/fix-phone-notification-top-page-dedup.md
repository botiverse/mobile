---
type: fixed
area: notifications
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复了手机端（保持默认开启「大屏桌面模式」设置时）连续点击同一会话的后台推送通知会重复压入多个会话页面、需要多次返回才能回到首页的问题；现在会复用栈顶已有会话页，并在当前会话内直接拉取定位并高亮新通知对应的目标消息。同时修正了紧凑手机端在首页点击「收藏」和「草稿」时误走宽屏内联分栏而非独立页面的问题。

## en

Fixed consecutive background push notifications for the same conversation pushing duplicate conversation pages on phones with the default "Desktop mode on large screens" setting enabled: the top-page deduplication check now checks whether the wide dual-pane layout is actually active rather than only the user preference, reuses the existing top conversation page so a single Back returns to Home, and jumps to and highlights the newly tapped notification's target message in place (loading its context if not yet in the timeline). Also fixed Home Saved and Drafts shortcuts on compact phones mistakenly routing through the wide-rail inline pane instead of pushing their standalone mobile pages.

## ja

デフォルトで「大画面でデスクトップモードを使用」がオンのスマートフォン環境において、同じ会話のバックグラウンドプッシュ通知を連続してタップすると会話ページが重複してスタックされ、戻る操作が複数回必要になる問題を修正しました。トップページ重複判定で設定値だけでなくワイドデュアルペインが実際に有効かを確認し、既存の会話ページを再利用したうえで新しい通知の対象メッセージへその場で移動・ハイライト（未読み込みの場合はコンテキストを取得）するようにしました。あわせて、コンパクトなスマートフォンでホームの「保存済み」「下書き」をタップした際にワイドレール用インライン表示へ遷移していた問題も修正しました。
