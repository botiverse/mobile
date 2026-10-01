---
"raft-mobile": minor
---

# English
Implement client-side background prefetching of latest message previews for direct messages on cold start when preview data is absent. Parses `lastMessagePreview` and `lastMessageSenderName` from channel details, bounds previews to 140 characters, and updates in-memory DM lists non-blockingly without touching unread counts or persisting to disk.

# 中文
实现移动端冷启动时无预览私聊会话的后台静默预取与补全。支持从 Channel Detail 解析 `lastMessagePreview` 和 `lastMessageSenderName`，按 140 字符截断与空白折叠规范化，非阻塞补全第二行摘要，严格隔离未读计数且不持久化到磁盘缓存，失败平滑降级为空。
