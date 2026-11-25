---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

优化了历史定位进入会话时上下边界同时翻页加载的展示：更早与更新消息仍保持并行拉取，时间线界面同一时刻最多只显示一个边界加载提示并在错峰完成时平滑交接，避免同一屏同时出现上下两个加载指示器；错误重试按钮、历史限制提示与「对话开始」/离线边界提示不受影响。

## en

Enforced a single visible boundary loading indicator across conversation timelines even when older and newer pages load in parallel (such as entering a short historical window). Staggered loads keep the active indicator without flickering and hand off cleanly when it finishes, while retry buttons, history paywall, and non-loading boundary labels remain visible.

## ja

履歴メッセージから会話を開いた際に前後のページ読み込みが並行実行されても、タイムライン上に表示される境界の読み込みインジケーターは常に最大 1 つに統一しました。読み込みのタイミングがずれた場合も表示がちらつかず完了時にスムーズに引き継ぎ、再試行ボタン・履歴制限表示・「会話の先頭です」やオフライン境界表示は引き続き表示されます。
