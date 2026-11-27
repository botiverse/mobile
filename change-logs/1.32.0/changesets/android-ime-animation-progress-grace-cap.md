---
type: fixed
area: composer
platforms: [android]
rollout: full
---

## zh-CN
修复了高刷新率弹簧软键盘动画（如小米 HyperOS Android 16 120Hz，实际动画耗时 450–900ms 但系统上报时长仅 285ms）在动画播放中途被提前判定超时，导致输入框与列表布局发生跳变与多余重排的问题。现在每次收到键盘动画进度帧会顺延短空闲宽限期（250ms），并保留 3 秒绝对上限，既保持键盘跟随流畅又确保系统丢失结束回调时安全兜底。

## en
Fixed an issue where long spring keyboard animations on high-refresh-rate Android devices (such as Xiaomi HyperOS on Android 16 120Hz, running 450–900ms while reporting a 285ms duration) prematurely timed out mid-animation, snapping the composer layout and triggering extra relayouts. Keyboard animation tracking now extends a short 250ms idle grace window while frames keep arriving up to a 3-second hard cap, keeping keyboard motion smooth while still recovering safely if the system drops the end callback.

## ja
高リフレッシュレートのスプリング式キーボードアニメーション（Xiaomi HyperOS Android 16 120Hz など、システム申告時間が 285ms のまま実動作が 450〜900ms に及ぶ端末）でアニメーション途中にタイムアウト誤判定が起き、入力欄のレイアウト飛びや余分な再レイアウトが発生する不具合を修正しました。アニメーションフレームが届いている間は 250ms の短いアイドル猶予を延長しつつ 3 秒の絶対上限を設けることで、滑らかな追従と終了通知欠落時の安全な復旧を両立します。
