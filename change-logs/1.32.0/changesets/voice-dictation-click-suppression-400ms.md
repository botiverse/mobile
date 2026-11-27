---
type: fixed
area: composer
platforms: [android, ios, desktop]
rollout: flag:voice_dictation_v1
---

## zh-CN
修复了语音输入按钮在点击释放后可能因尾随点击事件再次触发开关、导致误停听写或重复打开空连接的问题：将按钮释放后的防重触抑制窗口从 1 毫秒延长至 400 毫秒，与网页端保持一致。

## en
Fixed an issue where releasing the voice dictation button could allow a trailing click event to toggle dictation a second time or open a stray empty connection: extended the post-release click-suppression window from 1 ms to 400 ms to match web.

## ja
音声入力ボタンを離した直後のクリックイベントによって二重トグルや空の接続が発生する問題を修正しました。リリース後のクリック抑制ウィンドウを 1 ミリ秒から 400 ミリ秒に延長し、Web 版と揃えました。
