---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1324
rollout: full
---

## zh-CN

修复连续从 Activity 或通知打开同一回复串内不同消息时，第二次打开偶发仍滚动到或高亮上一条消息的问题。回复串现在会始终采用最新一次打开请求的目标消息。

## en

Fixed consecutive Activity or notification opens into different messages in the same reply thread sometimes scrolling to or highlighting the previous message. The reply thread now always follows the latest open target.
