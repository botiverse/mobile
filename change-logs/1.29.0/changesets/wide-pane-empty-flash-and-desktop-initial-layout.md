---
type: fixed
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: 5302
rollout: full
---

## zh-CN

修复宽屏双栏在冷启动、切换服务器或切回消息页时，右侧详情区短暂闪烁「选择一个会话」空状态的问题，同时修复窄屏拉回宽屏后不再自动打开默认会话，以及桌面端首帧先以单栏闪一下再切回宽屏布局的问题。

## en

Fixed the wide two-pane layout briefly flashing the "Select a conversation" placeholder on cold start, server switches, and tab returns, restored automatic default conversation landing after resizing from narrow back to wide, and eliminated the single-column first-frame flash when opening wide desktop windows.

## ja

ワイド 2 ペイン表示の起動時・サーバー切替時・タブ復帰時に右ペインで「会話を選択」の空画面が一瞬表示される不具合を修正しました。あわせて、狭い画面から再びワイド表示へ広げた際にデフォルト会話が開かれなくなる問題と、デスクトップ版の初回フレームで一時的に 1 カラム表示がちらつく問題も解消しました。
