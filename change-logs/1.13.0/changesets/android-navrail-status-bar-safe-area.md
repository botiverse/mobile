---
type: fixed
area: shell
platforms: [android]
rollout: full
pr: pending
---

# 中文

修复 Android 大屏（桌面模式）边到边安全区缺陷：导航 rail 右缘分隔线与页面顶部不再画进系统状态栏之下，私聊输入框不再被底部系统栏遮挡。Android 宿主现在通过 WindowInsets 监听把真实的顶部/底部系统栏像素高度持续推给共享层（含首次 attach 后回调与后续变化），共享层与渲染器上报值取较大者生效；rail 分隔线从解析后的顶部安全区之下开始绘制，rail 底色保持边到边，嵌入式子页（大屏右侧 DM/讨论串）同样获得修正后的安全区。单栏竖屏布局、桌面模式开关与键盘弹起时输入框行为不变，不会产生双重让位。

# English

Fix Android large-screen (desktop mode) edge-to-edge safe-area defects: the navigation rail's right-edge divider and page headers no longer draw under the system status bar, and the DM composer no longer sits under the bottom system bar. The Android host now continuously publishes real top/bottom system-bar pixel heights to the shared layer via a WindowInsets listener (covering the post-attach dispatch and subsequent changes); the shared layer takes the larger of renderer-reported and host-measured values. The rail divider starts below the resolved top safe area while the rail band stays edge-to-edge, and embedded child pages (wide-screen DM/thread detail) receive the corrected insets as well. Single-pane portrait layout, the desktop-mode toggle, and keyboard-open composer behavior are unchanged with no double padding.
