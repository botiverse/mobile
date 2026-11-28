---
type: fixed
area: artifacts
platforms: [ios]
rollout: full
---

## zh-CN
修复了 iOS 端在 Raft 内置页面（如 Artifact 评论栏与移动端语音输入条）使用语音输入时麦克风权限被拒绝的问题：在校验主框架、自有源白名单与 `NSMicrophoneUsageDescription` 后，若系统麦克风权限已授权（或首次系统弹窗授权通过），将直接允许 Raft 自有页面的纯音频麦克风采集；所有第三方网页、沙箱内容 iframe、摄像头及混合音视频请求仍严格拒绝。

## en
Fixed voice dictation failing to access the microphone inside Raft's own in-app web pages on iOS (such as the Artifact comment box and mobile talk bar): after verifying main-frame origin match against the explicit own-origin allowlist and `NSMicrophoneUsageDescription`, Raft now grants audio-only WebView microphone capture once iOS microphone authorization is granted (prompting once when undetermined), while continuing to deny all third-party sites, iframes, camera, and mixed audio+video capture requests.

## ja
iOS で Raft 自身のアプリ内ページ（Artifact コメント欄やモバイルの音声入力バーなど）から音声入力を使う際にマイク権限が拒否される不具合を修正しました。メインフレームの同一オリジン一致と `NSMicrophoneUsageDescription` を検証したうえで、iOS のシステムマイク権限が許可された後（未決定時は初回のみシステムダイアログを表示）に Raft 自身のページの音声のみマイク入力を許可します。第三者サイト、iframe、カメラや音声＋映像の複合要求は引き続きすべて拒否されます。
