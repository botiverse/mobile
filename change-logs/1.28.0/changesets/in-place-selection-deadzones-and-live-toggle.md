---
type: fixed
area: thread
platforms:
  - android
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

修复开启「新消息菜单」（Beta）后长按消息正文可能选不中文字的问题：代码块短行与左对齐标题右侧现在可直接长按原位选字，长按正文段落间隙或折叠长消息下方区域时不再无响应，在设置里切换该开关后已打开的对话也会立即生效。

## en

Fixed cases where pressing and holding message text with "New message menu (Beta)" turned on failed to select text in place: long-pressing beside short code lines or left-aligned headings now selects in place, pressing non-text gaps or below a collapsed long message falls back to the message menu instead of doing nothing, and toggling the setting now takes effect immediately in already-open conversations.

## ja

「新しいメッセージメニュー」（ベータ）をオンにしているときにメッセージ本文を長押ししてもテキストを選択できない場合がある問題を修正しました。短いコード行や左揃えの見出しの右側でもその場で選択でき、段落間の余白や折りたたまれた長いメッセージの下を長押ししたときも無反応にならずメニューが開きます。また、設定の切り替えが開いている会話に即時反映されるようになりました。
