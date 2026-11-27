---
type: fixed
area: thread
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
修复打开定位消息（如消息链接或搜索跳转）后，在用户主动滚动前如果时间线视口高度发生变化（如置顶公告栏或底部输入区高度变化），目标消息未自动重新定位到可视区域的问题。

## en
Fixed initial focused message placement not re-aligning when the timeline viewport height changes (such as late chrome or composer height changes) before the user scrolls.

## ja
メッセージリンクなどで特定のメッセージにフォーカスして開いた直後、ユーザーがスクロールする前にタイムラインの表示領域の高さが変化した場合でも、対象メッセージの位置を自動的に再調整するように修正しました。
