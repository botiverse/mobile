---
type: fix
area: file-preview
platforms:
  - android
  - ios
  - ohos
pull_request: 3050
rollout: full
---

## zh-CN

`.ts` 附件不再被当成视频播放器打开。此前 Android 的 MIME 表会把 `.ts` 识别为 `video/mp2t`，再叠加扩展名映射后会误走视频预览，出现黑屏“视频播放失败”。现在 `.ts`/`.tsx` 会按文本/代码预览，`.m2ts`/`.mts` 这类明确的视频容器仍保持视频播放。

## en

`.ts` attachments no longer open in the video player. Android MIME detection can classify `.ts` as `video/mp2t`, which previously combined with extension mapping and routed TypeScript files to video preview, showing a black “video playback failed” state. Now `.ts`/`.tsx` resolve to text/code preview, while unambiguous video containers such as `.m2ts`/`.mts` still use video playback.
