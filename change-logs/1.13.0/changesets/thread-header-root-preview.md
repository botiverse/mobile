---
type: changed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 0
rollout: full
---

## zh-CN

消息列顶栏标题改为显示根消息预览（`@发送者: 一行摘要`，纯图片/文件显示 `[图片]`/`[文件]`），副标题行同时显示回复数；根消息尚未加载时仍显示「消息列」。

## en

A thread's header now shows a one-line preview of its root message (`@sender: text`, or `[Image]`/`[File]`) instead of "Thread", and the subtitle line shows the reply count. Until the root message loads, the header still reads "Thread".
