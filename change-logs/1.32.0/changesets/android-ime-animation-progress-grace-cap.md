---
type: fixed
area: composer
platforms: [android]
rollout: full
---

## zh-CN
重构了 Android 键盘高度同步机制：将软键盘高度统一收口为单一真值，由系统逐帧动画回调直接驱动并按系统渲染帧对齐收尾，移除了原有的固定毫秒超时阈值，修复高刷新率设备（如 120Hz 弹簧键盘动画）在动画播完前被提前截断导致的输入框跳动与掉帧感，同时继续防护厂商丢失动画结束回调的情况。

## en
Refactored Android keyboard inset tracking to a single authoritative height source driven directly by platform per-frame animation callbacks and reconciled with display vsync frames, removing all hardcoded millisecond timeouts. This fixes premature mid-animation cutoffs and composer jumps on high-refresh-rate spring keyboard curves (such as 120Hz devices) while still recovering cleanly if an OEM drops animation start/end callbacks.

## ja
Android のキーボード高さ同期を単一の真値ソースへ刷新し、システムから届くフレーム単位のアニメーションコールバックで直接更新しつつ表示の vsync フレームと整合させる方式に変更し、固定ミリ秒のタイムアウトを撤廃しました。120Hz 端末などのスプリング型キーボードアニメーションが途中で打ち切られて入力欄が跳ねる不具合を解消しつつ、メーカー端末で開始・終了コールバックが欠落した場合の復帰も維持します。
