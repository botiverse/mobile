---
type: fixed
area: artifacts
platforms: [android]
rollout: full
---

## zh-CN
修复了 Android 端在 Raft 内置页面（如 Artifact 评论栏）点击语音输入时无法申请麦克风权限的问题：在用户已授予系统麦克风权限（或完成首次系统权限弹窗授权）后，应用会自动允许 Raft 自有页面的麦克风采集而不再弹出额外确认框；同时继续严格拒绝所有第三方网页、沙箱内容框架以及摄像头等其他媒体权限请求。

## en
Fixed voice dictation failing to access the microphone inside Raft's own in-app pages on Android (such as the Artifact comment bar): once Android's system microphone permission is granted (prompting once when not yet granted), Raft now allows audio capture for its own pages without an extra in-app popup, while continuing to deny microphone and camera requests from third-party sites, sandboxed content frames, and all video or mixed media requests.

## ja
Android で Raft 自身のアプリ内ページ（Artifact のコメント欄など）から音声入力を使う際にマイク権限が拒否される不具合を修正しました。Android のシステムマイク権限が許可された後（未許可の場合は初回のみシステムダイアログを表示）、追加の確認ポップアップなしで Raft 自身のページの音声入力を許可します。第三者サイトやサンドボックス内のコンテンツフレーム、カメラやその他のメディア権限要求は引き続きすべて拒否されます。
