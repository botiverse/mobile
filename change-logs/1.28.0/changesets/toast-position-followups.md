---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

补充 toast 定位与抽屉手势的边界修复：iPad 接硬件键盘时 toast 底部避让不再低于系统安全区（避免压在快捷键条下）；强制 RTL 下使用 `place(x, y)` 避免水平偏移；下滑关闭抽屉被调用方拒绝时平滑滑回原高度而非一帧瞬弹；嵌套页面栈正确继承避让开关并补齐真实组件接线测试。

## en

Follow-up fixes for toast positioning and drawer gestures: keep toast bottom clearance at least as tall as system `safeDrawing` when an iPad hardware keyboard shortcut bar is shown, use `place(x, y)` to avoid double-mirroring under forced RTL, smoothly animate a drawer back to its snap point when a drag-to-dismiss is declined, and inherit parent toast-avoidance state in nested `RaftStackScene` with end-to-end wiring UI tests.

## ja

トースト配置とドロワージェスチャーのフォローアップ修正を行いました。iPad のハードウェアキーボード接続時にトーストがシステムセーフエリア（ショートカットバー）の下に重ならないようにし、RTL 環境での位置ずれを防ぐため `place(x, y)` に統一、ドラッグでの閉じる操作が拒否された際にドロワーが滑らかに元の高さへ戻るよう改善し、ネストされた `RaftStackScene` での回避状態継承と実コンポーネント配線の UI テストを追加しました。
