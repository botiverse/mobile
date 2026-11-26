---
type: fixed
area: ui
platforms:
  - android
  - desktop
rollout: full
---

## zh-CN

修复了在 Android（及桌面端）上打开弹窗或底部抽屉（如消息“选择文本”面板点击“复制”）后，Toast 提示被弹窗窗口遮挡在底层导致看不见的问题：所有 `RaftWindowDialog` 弹窗窗口现在会在顶层挂载共享的 `RaftToastHost` 浮层，并通过按层级 LIFO 协调保证同一时间仅由最顶层窗口绘制提示与驱动倒计时，关闭弹窗时仍在显示的 Toast 会无缝交回主窗口且不重复播放入场动画。

## en

Fixed toasts being hidden behind open sheets and dialogs on Android (and Desktop)—such as tapping Copy inside the Select Text sheet—because `RaftWindowDialog` renders in a separate window/layer above the host Activity's toast layer. `RaftWindowDialog` now mounts the shared `RaftToastHost` overlay on top of its scrim and content, coordinated via layer-aware LIFO ownership on `RaftToastHostState` so only the topmost window renders toasts and runs timers without duplication, and still-live toasts hand back seamlessly to the host window when a dialog closes.

## ja

Android（およびデスクトップ）でシートやダイアログ（メッセージの「テキストを選択」シートで「コピー」をタップした際など）を開いたままトーストを表示すると、ダイアログウィンドウの裏側に隠れて見えなくなる問題を修正しました。`RaftWindowDialog` が最前面に共有 `RaftToastHost` オーバーレイを配置し、`RaftToastHostState` のレイヤー対応 LIFO 制御によって常に最上位のウィンドウのみがトーストの描画とタイマーを実行するようにしたため、重複表示せずにダイアログを閉じた後も元のウィンドウへシームレスに引き継がれます。
