---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
rollout: immediate
---

## zh-CN

修复三项移动端诊断与附件上传稳定性问题：修复 Android 诊断队列在历史上任意一次日志丢弃或异常后 `flush()` 永久返回失败导致反馈工单丢失诊断压缩包的问题（并在失败/取消路径补充告警日志）；恢复「设置 → 诊断 / 关于」复制与分享中的脱敏近期日志（按安全上限截断，反馈分享卡片仍保持精简环境信息）；修复 Android 聊天附件/拍照选择器在相机状态重建或系统异常关闭未回调结果时永久卡死导致无法再次上传文件的问题。

## en

Fixed three mobile diagnostics and attachment upload reliability issues: stopped historical dropped/failed log counters from permanently poisoning Android diagnostics `flush()` (which caused Feedback tickets to be filed without the diagnostics zip) with best-effort archive/export fallback and explicit failure logging; restored bounded, sanitized recent logs in Settings → Diagnostics / About Copy & Share across Android and iOS (keeping Hands feedback ticket share cards compact); and fixed the Android attachment/camera picker latch getting permanently stuck (`409 attachment_picker_busy`) when a camera result arrives without state or an OEM ROM dismisses the picker without delivering an ActivityResult.

## ja

モバイルの診断ログおよび添付ファイルアップロードに関する 3 件の信頼性不具合を修正しました。Android の診断ログ `flush()` が過去のログ欠落・失敗カウンタによりプロセス終了まで恒久的に失敗し、フィードバック送信時に診断 ZIP が添付されなくなる問題を修正し、失敗・キャンセル経路のログを補強しました。また、「設定 → 診断／このアプリについて」のコピー・共有に機密除去済みの最新ログを安全なサイズ上限付きで復元（Android・iOS 共通）し、Android のファイル／カメラピッカーが異常終了や状態再生成で結果を返さない場合に以降の添付が恒久的にブロックされる不具合を修正しました。
