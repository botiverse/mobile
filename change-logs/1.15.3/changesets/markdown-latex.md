---
type: added
area: messages
platforms:
  - android
  - ios
pull_request: 3511
rollout: full
---

## zh-CN

消息里支持 LaTeX 数学公式（$…$ 和 $$…$$），规则和网页版一致：行内公式与文字对齐，单独成行的 $$ 公式居中显示，过宽时可左右滑动，深色模式下跟随文字颜色。价格里的 $（如「$5 到 $10」）不会被误认成公式；写错的公式显示为红色源码。

## en

Messages render LaTeX math ($…$ and $$…$$) by the same rules as the web: inline formulas sit on the line of text, a $$ formula on its own lines is centered and scrolls sideways when it is too wide, and formulas follow the text color in dark mode. Dollar amounts such as "$5 to $10" are not mistaken for math, and a formula with an error shows its source in red.
