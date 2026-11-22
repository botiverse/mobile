---
type: fixed
area: performance
platforms: [android, ios, desktop]
rollout: full
pull_request: 5421
---

## zh-CN

优化选图后进入图片预览与编辑页的加载速度，减少高分辨率照片解码时的卡顿并避免预览切入编辑时重复解码，同时保持保存导出时的原图清晰度。

## en

Reduced loading delay and hitching when opening high-resolution photos in preview and the image editor by avoiding redundant image decodes and reusing the preview image when entering edit mode, while preserving full-resolution exports.

## ja

高解像度写真のプレビューおよび画像編集画面を開く際の読み込み遅延と引っかかりを軽減し、編集画面への切り替え時の再デコードを防ぎつつ、書き出し時の高解像度を維持します。
