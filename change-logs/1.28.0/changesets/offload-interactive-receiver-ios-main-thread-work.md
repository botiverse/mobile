---
type: fixed
area: performance
platforms: [android, ios]
pull_request: 0
rollout: full
---

## zh-CN
将安卓与 iOS 上一批交互和后台广播路径中的磁盘读写、图片解码及系统 IPC 移出主线程：安卓端异步执行设置页存储/诊断/关于/开源许可与通知检查、长图分享的解码拼接与 PNG 写入、通知滑动关闭与桌面小组件/应用更新广播、权限回调上报及保活偏好联动，缓存折叠屏铰链与系统 WebView 信息，并延后首帧后的截屏监听注册；iOS 端改为在后台打开本地 PDF 文档、异步执行诊断读写清理，并将粘贴图片时的 ImageIO 校验移出主线程。

## en
Offload main-thread disk I/O, image decoding, and system IPC across Android and iOS interactive and broadcast paths: on Android, move Settings storage/diagnostics/about/licenses and notification checks, long-image share decoding/stitching and PNG encoding, notification dismiss/widget/update BroadcastReceivers, permission-result reporting, and keep-alive preference updates off the main thread, cache hinge-sensor and WebView provider queries, and defer Android 14+ screen-capture callback registration past the first frame; on iOS, open local PDF documents asynchronously, run diagnostics load/copy/share/clear off the main thread, and move clipboard image ImageIO validation off the main thread while keeping the paste-action exemption intact.

## ja
Android・iOS の操作時およびブロードキャスト処理でメインスレッドを塞いでいたディスク I/O、画像デコード、システム IPC をバックグラウンドへ移行しました。Android では設定画面のストレージ・診断・アプリ情報・ライセンス・通知確認、長尺シェア画像のデコード／結合および PNG 書き出し、通知スワイプ削除・ウィジェット・更新ダウンロードの各レシーバー、権限結果報告、バックグラウンド維持設定の反映を非同期化し、ヒンジセンサーと WebView プロバイダー情報をキャッシュするとともに、Android 14+ のスクリーンショット検知登録を初回フレーム後に延期しました。iOS ではローカル PDF の読み込み、診断ログの取得・コピー・共有・削除、クリップボード画像の ImageIO 検証をメインスレッド外で実行します。
