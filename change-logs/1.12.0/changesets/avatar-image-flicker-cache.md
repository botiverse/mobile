---
type: fixed
area: chat
platforms:
  - android
pull_request: null
rollout: full
---

## zh-CN

修复打开服务器切换器及应用内头像/图片重复重渲染、闪烁的问题：
1. 为 Kuikly 底层持久化头像（managed_avatar）引入 8MB 内存 Bitmap LRU 缓存与单飞控制，命中时同步返回，杜绝磁盘读取与重复解码；
2. 优化 Compose 侧头像加载状态管理，首帧通过活跃会话快照即时获取已提交模型，消除从裸 HTTP URL 到 managed_avatar 的首帧模型抖动；
3. 服务器切换器在存在有效头像配置时抑制黄色 fallback 字母的底层绘制，避免解码间隙的视觉闪烁。

## en

Fixed repeated re-rendering and flickering of avatars and images across the app and server switcher:
1. Introduced an 8MB in-memory Bitmap LRU cache and single-flight deduplication for Kuikly managed avatars, returning synchronously on cache hits to eliminate redundant disk reads and BitmapFactory decoding;
2. Optimized Compose avatar state management to peek committed models synchronously from active session snapshots on frame 1, eliminating model flapping between raw HTTP URLs and managed_avatar;
3. Suppressed fallback label rendering in the server switcher when an avatar is present, eliminating high-contrast visual flashing during decode intervals.
