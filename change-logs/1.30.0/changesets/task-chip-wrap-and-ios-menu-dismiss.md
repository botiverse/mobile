---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复消息中带频道或会话前缀的任务引用芯片（如 `#wg-mobile-sync:#276`、`dm:@artin:#170`）在连字符或冒号处被硬折行的问题，并在超长芯片被迫折行时为折断边缘保留留白；同时修复 iOS 展开原生顶部菜单后点击菜单外部收起时，点击穿透触发下方消息或芯片的问题。

## en

Keep qualified task chips (such as `#wg-mobile-sync:#276` and `dm:@artin:#170`) intact on one line instead of breaking after hyphens or colons, with subtle breathing room on split edges when an ultra-long chip must wrap. On iOS, tapping outside an open native navigation bar menu now dismisses the menu cleanly without clicking through to messages or chips underneath.

## ja

チャンネルや会話プレフィックス付きのタスク参照チップ（`#wg-mobile-sync:#276` や `dm:@artin:#170` など）がハイフンやコロンの後ろで途切れて折り返されないようにし、非常に長いチップが折り返される場合も分割端に余白を保つようにしました。また iOS でネイティブメニューの外側をタップして閉じた際、背面のメッセージやチップにタップが貫通しないように修正しました。
