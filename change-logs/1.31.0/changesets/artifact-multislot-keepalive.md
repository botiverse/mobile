---
type: fixed
area: artifacts
platforms: [android, ios]
rollout: general
---

## zh-CN
修复在多个 Artifact 之间来回切换时每次都会重新加载页面和沙箱内容的问题：新 WebView 容器（`compose/web-container`）现按 Artifact 维度启用多槽位 LRU 保活复用（最多保留 3 个已打开的 Artifact 槽位并响应系统内存压力回收），切换回已打开过的 Artifact 时零重载、无加载页闪烁且保留原有滚动位置。

## en
Fixed Artifacts reloading their page and sandbox iframe every time you switch back and forth between them: `compose/web-container` now uses per-Artifact multi-slot LRU keep-alive pooling (keeping up to 3 recent Artifacts warm with memory-pressure trimming), so returning to a recently viewed Artifact resumes instantly with zero reload, no loading splash flash, and preserved scroll position.

## ja
複数の Artifact を行き来するたびにページとサンドボックス内容が毎回再読み込みされる問題を修正しました。新しい WebView コンテナ（`compose/web-container`）で Artifact 単位のマルチスロット LRU キープアライブ（最大 3 スロット保持・メモリ逼迫時の自動解放）を有効化し、直前に開いた Artifact へ戻った際にリロードやローディング画面のちらつきなくスクロール位置を維持したまま即座に表示します。
