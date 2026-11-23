---
type: fixed
area: thread
platforms: [android, ios, desktop]
pull_request: 5469
rollout: full
---

## zh-CN

修复在电脑版（以及连接了触控板或鼠标的 iPad、Android 设备）上用触控板或鼠标滚轮向上滚动讨论串或会话时，“正在加载更早的消息…”一直不结束、更早的消息始终不出现的问题；消息不足一屏、无法滚动的会话也会自动加载更早的消息。

## en

Fixed "Loading older messages…" never finishing when you scroll up a thread or conversation with a trackpad or mouse wheel on the desktop app (and on an iPad or Android device with a trackpad or mouse): the older messages now load. A conversation too short to scroll now loads its older messages by itself.

## ja

デスクトップ版（およびトラックパッドやマウスを接続した iPad・Android 端末）で、スレッドや会話をトラックパッドやマウスホイールで上へスクロールしても「以前のメッセージを読み込み中…」が終わらず、以前のメッセージが表示されなかった問題を修正しました。スクロールできないほど短い会話でも、以前のメッセージを自動で読み込むようになりました。
