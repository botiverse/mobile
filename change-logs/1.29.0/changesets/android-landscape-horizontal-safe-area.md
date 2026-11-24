---
type: fixed
area: layout
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复手机横屏模式下的左右安全区避让问题：Android 宿主现已上报物理左右显示挖孔与侧边导航栏安全区（含 90°/270° 旋转切换、转回竖屏清零以及新推入页面首帧无缝继承），并同步弹窗窗口的挖孔绘制策略；同时补齐底部标签栏、图片灯箱、文件预览、成员页、电脑页、制品页、频道条幅与线程辅助行等共享界面的横向安全区避让，保持背景全屏铺满、交互与文字内容避开物理边缘且无重复留白。

## en

Fixed horizontal safe-area avoidance in phone landscape mode: the Android host now reports physical left/right display cutout and side navigation bar insets (updating cleanly across 90°/270° rotations, clearing back to zero in portrait, and seeding pushed screens on their first frame) with matching dialog cutout mode, and shared surfaces (bottom tab bar, image lightbox backdrop bleed, file previews, members, computers, artifacts, channel banners/settings, auth, and thread timeline rows/overlays) now keep backgrounds edge-to-edge while keeping readable and interactive content clear of physical edges with zero double-padding.

## ja

スマートフォンの横向き表示における左右セーフエリア回避を修正しました。Android ホストが物理的な左右のディスプレイカットアウトとサイドナビゲーションバーのインセットを共有レイヤーへ通知するようになり（90°/270° 回転や縦向き復帰時のクリア、遷移先画面の初回フレーム引き継ぎ、ダイアログのカットアウト描画に対応）、下部タブバー・画像ライトボックス・ファイルプレビュー・メンバー・コンピューター・アーティファクト・チャンネル設定・スレッド補助行などの共有 UI でも背景を端まで広げたままコンテンツを安全領域内に収め、二重パディングを防ぎます。
