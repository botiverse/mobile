---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2614
rollout: full
---

## zh-CN

对齐移动端消息内标签引用解析过程的视觉与交互反馈：在解析中状态下对齐 Web 体验展示动态加载菊花 Spinner 并保留 Thread 引用蓝色胶囊样式；将激活 Action 标识折叠入 Markdown 注解版本号以彻底击穿 AnnotatedString 缓存防止加载状态被吞；修复本地同一会话内点击标签时未触发消息滚动高亮的问题，并在每次定位消息时递增请求 nonce 确保连续点击同一标签能可靠重触发定位。

## en

Aligned mobile message reference pending resolution UI/UX with Web: display an animated loading spinner during pending resolution while preserving thread reference blue chip styling; fold active action IDs and keys into markdown annotation revision to bust memoization caching and prevent suppressed pending states; route same-conversation references to in-page scroll and highlight rather than redundant navigation, and increment focus request nonce on every focus message intent to ensure repeat taps reliably re-trigger viewport anchoring.
