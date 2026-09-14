---
type: added
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2443
rollout: full
---

## zh-CN

对话串中标注为 mermaid 的代码块现在直接渲染成图表：消息内呈现绘制好的流程图、时序图等图形，不再只显示源码文本，并可通过图表右下角的「−」与「+」在 50% 到 300% 之间缩放、点击中间的百分比一键回到 100%。若图表源码无法解析或排版失败，消息会退回原来的代码块展示，复制入口保持不变。

## en

Code blocks tagged as mermaid in threads now render as diagrams: the message shows the drawn flowchart, sequence diagram, or other figure instead of only source text, with minus and plus controls in the diagram's bottom-right corner for zooming between 50% and 300% and a tap on the percentage to return to 100%. If a diagram's source cannot be parsed or laid out, the message falls back to the original code block and its copy option stays available.
