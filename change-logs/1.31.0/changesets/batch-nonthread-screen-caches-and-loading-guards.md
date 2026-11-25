---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

优化多个非对话页面的首帧缓存与加载保护：重新进入「设置 › 电脑」、Agent 详情（用量、应用事件与活动概览）、「我的反馈」和 Artifacts 首页时会直接显示已缓存内容并在后台静默刷新，不再反复闪烁空白或加载占位；同时修复了 Agent 应用事件详情无加载器时一直停留在「加载中」、活动概览加载更多或回复抛异常后卡在加载状态，以及多个页面首次加载期间仍可触发下拉刷新的问题。

## en

Eliminated redundant loading flashes and stuck-loading states across non-thread screens: revisiting Settings › Computers, Agent Detail (Usage, App Events, and Activity Overview), My Feedback, and Artifacts Home now renders cached content on frame 1 while refreshing in the background. Also fixed Agent App Events hanging in "Loading…" when no detail loader is available, Activity Overview `loadMore`/`loadReply` sticking in loading on thrown exceptions, and pull-to-refresh firing during initial full-screen loading states.

## ja

スレッド以外の各画面における初回フレームのキャッシュ表示と読み込み保護を改善しました。「設定 › コンピューター」、Agent 詳細（使用量・アプリイベント・アクティビティ概要）、「マイフィードバック」、Artifacts ホームへ戻った際にキャッシュ済みの内容を即座に表示しつつバックグラウンドで更新し、読み込みプレースホルダーのちらつきを防ぎます。また、Agent アプリイベント詳細でローダー未設定時に「読み込み中…」のまま止まる問題、アクティビティ概要の追加読み込みや返信取得で例外発生時に読み込み状態が解除されない問題、初回読み込み中にプル更新が発動する問題も修正しました。
