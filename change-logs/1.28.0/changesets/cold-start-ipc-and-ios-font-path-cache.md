---
type: improved
area: performance
platforms: [android, ios]
pull_request: 0
rollout: full
---

## zh-CN

缩短 Android 冷启动阶段的主线程等待，减少首次打开时的卡顿；优化 iOS 端字体查找开销，让界面加载更顺畅。

## en

Reduce main-thread startup work on Android so cold launches open more smoothly, and cache repeated bundled font lookups on iOS to speed up screen rendering.

## ja

Android のコールドスタート時のメインスレッド待機を減らして初回起動のカクつきを軽減し、iOS のフォント参照のオーバーヘッドを抑えて画面表示をより滑らかにしました。
