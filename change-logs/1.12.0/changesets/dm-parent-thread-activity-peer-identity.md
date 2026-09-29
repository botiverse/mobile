---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

对齐飞书话题卡片展示：当一条话题（Thread）来源于私聊会话时，Activity 话题卡片归属标签显示私聊对象 `@对象名`（不再误用频道 `#` 标签），第一行主标题展示 `@对象名: 根消息摘要`，第二行保持 `回复者: 最新回复`；话题详情页顶栏同样以 `@对象名` 标注私聊归属。私聊对象名通过会话目录已缓存的 DM 列表按 channel id 惰性水合，普通频道话题卡片不受影响。

## en

Align the Feishu-style topic card for reply threads that originate inside a direct message: the attribution tag on the Activity topic card now shows the DM peer as `@peer` instead of a channel `#` tag, the primary line renders `@peer: root message preview`, the secondary line stays `sender: latest reply`, and the thread detail header annotates the DM origin as `@peer` as well. Peer display names hydrate lazily from the cached DM channel list keyed by channel id; ordinary channel thread cards are unchanged.
