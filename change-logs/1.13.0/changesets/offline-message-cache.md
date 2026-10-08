---
type: added
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2826
rollout: full
---

## zh-CN

新增频道离线消息缓存：打开看过的频道会先显示上次看到的最近消息，没有网络或网络较慢时也能立即看到内容，并提示"当前为离线内容，可能不是最新"；之前为空的频道同样会显示为空，而不是一直加载。网络恢复后会自动刷新为最新消息，刷新失败时会在后台逐步重试。离线时无法加载更早的消息。该功能由服务端开关 `sync_core_messages_v1` 控制，已对所有服务器开启，只有 1.13.0 及以后的版本读取这个开关。

## en

Added an offline message cache for channels. A channel you've opened before shows the latest messages you last saw right away, even offline or on a slow network, with a note that the content is offline and may be out of date. A channel that was empty shows as empty instead of loading indefinitely. When the connection comes back the channel refreshes on its own, and a failed refresh keeps retrying in the background. Older messages can't be loaded while offline. The feature is controlled by the server flag `sync_core_messages_v1`, which is on for all servers; only 1.13.0 and later read this flag.
