---
type: fixed
area: browser
platforms:
  - ios
rollout: full
---

## zh-CN

修复了在 iOS 内置浏览器或 Raft 应用页面请求麦克风权限时应用直接闪退的问题：应用现已声明麦克风用途说明及其多语言文案，并在 iOS WebView 权限代理中新增防退化校验，仅在所需系统隐私说明完备时弹出授权提示，否则安全拒绝而不再触发系统崩溃。

## en

Fixed a crash on iOS when a web page or Raft App opened in the in-app browser requests microphone access: Raft now declares `NSMicrophoneUsageDescription` across all supported locales and guards WebKit media capture permission prompts so any missing privacy usage description is safely denied instead of crashing the app.

## ja

iOS のアプリ内ブラウザや Raft アプリで Web ページがマイク権限を要求した際にアプリがクラッシュする不具合を修正しました。すべての対応言語でマイクの利用目的説明を追加するとともに、iOS の WebView 権限ハンドラで必要なプライバシー説明の有無を確認し、未設定の場合はクラッシュさせず安全に拒否するようにしました。
