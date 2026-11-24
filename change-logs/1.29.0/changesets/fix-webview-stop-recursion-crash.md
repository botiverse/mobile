---
type: fixed
area: browser
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复了在应用内网页面板跳转到外部路由（如未启用原生登录弹窗时点「用 Raft 登录」）触发停止加载时，可能引发同步无限递归导致应用崩溃的问题。

## en

Fixed a crash where stopping a deflected navigation inside the in-app web panel (such as tapping "Log in with Raft" when redirected to the system browser) could synchronously recurse until the stack overflowed.

## ja

アプリ内ウェブパネルで外部へリダイレクトされる遷移を停止する際、状態通知が同期的に無限再帰してアプリがクラッシュする不具合を修正しました。
