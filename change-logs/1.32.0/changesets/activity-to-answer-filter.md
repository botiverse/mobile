---
type: added
area: activity
platforms: [android, ios, desktop]
rollout: behind-flag
---

## zh-CN
动态（Activity）筛选菜单新增「待回答」视图（受服务端特性开关 `to_answer_list` 控制），集中展示当前服务器中等待你处理的问题、确认和请求卡片。支持通过 `GET /api/actions/to-answer` 加载列表与实时计数、订阅 `to-answer:changed` 实时更新（新卡片防抖重拉、结束卡片立即扣减计数并停留 2 秒展示结束原因后淡出）、请求截止时间到期自动结束、断线重连自动重拉，以及点击行直达对应会话或话题中的卡片消息。开关关闭时隐藏该选项并自动回退到「全部」。

## en
Activity's Filter menu now offers a "To answer" view behind the `to_answer_list` server feature flag, listing open question, confirm, and request cards waiting on you in the current server. It loads from `GET /api/actions/to-answer` with live counts in the menu, active filter chip, and header subtitle, stays live via `to-answer:changed` realtime sync (debounced refetch on opened cards, immediate count drop with a 2-second ended linger and fade before removal), handles deadline expiry and reconnect refetches cleanly, and opens the card's conversation or thread at its message on tap. When the flag is off, the choice is hidden and any previously saved preference falls back to All.

## ja
アクティビティのフィルターメニューに、サーバー機能フラグ `to_answer_list` で制御される「未回答」ビューを追加しました。現在のサーバーで回答待ちの質問・確認・リクエストカードを `GET /api/actions/to-answer` から一覧表示し、リアルタイムイベント `to-answer:changed` によるライブ更新（新規カードのデバウンス再取得、終了カードの即時カウント減少と 2 秒間の状態表示・フェードアウト）、期限切れの自動処理、再接続時の再取得、タップ時の対象メッセージ・スレッドへの直接移動に対応しています。フラグがオフの場合は選択肢を非表示にし、保存済みフィルターは「すべて」にフォールバックします。
