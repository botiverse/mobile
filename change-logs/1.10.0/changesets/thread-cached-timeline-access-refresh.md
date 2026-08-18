---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2180
rollout: full
---

## zh-CN

修复从 Activity 等入口重复进入已访问过的 Thread 时，即使本机已有消息缓存仍会长时间显示“加载中”的问题。现在聊天页会立即展示与当前账号和 Thread 匹配的本机缓存，同时在后台刷新访问权限和最新消息；没有缓存、切换到非聊天标签或权限明确失败时仍保持原有的安全加载与错误状态，发送入口也会等权限确认后才开放。

## en

Fixed previously visited threads showing a prolonged loading screen when reopened from Activity or similar entry points even though matching messages were already cached locally. The Chat tab now renders the current account's accepted local timeline immediately while access and new messages refresh in the background. Uncached entries, non-Chat tabs, and authoritative access failures keep their existing safe loading or error states, and sending remains disabled until access is confirmed.
