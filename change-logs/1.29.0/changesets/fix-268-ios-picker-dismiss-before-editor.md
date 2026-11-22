---
type: fixed
area: performance
platforms:
  - ios
rollout: full
pull_request: 5422
---

## zh-CN

修复 iOS 选图或拍照返回后，图片编辑器在相册/相机收起动画尚未结束时提前触发打开、被导航队列阻塞等待的问题；收起动画与导出缓存改为并行执行并在收起完成后直接打开编辑页。

## en

Fixed iOS opening the image editor while the photo picker or camera sheet was still dismissing and getting queued behind the transition; dismissal and export/cache now run in parallel and open the editor cleanly once dismissal finishes.

## ja

iOS で写真選択またはカメラ撮影の終了直後に、シートの閉じるアニメーション完了前に画像エディタを開こうとして画面遷移待ちになる問題を修正しました。閉じる処理と書き出し・キャッシュを並列実行し、閉じた直後にエディタを開くようにしています。
