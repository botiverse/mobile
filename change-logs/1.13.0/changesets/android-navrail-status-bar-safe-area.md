---
"raft-mobile": patch
---

# English
Fix Android large-screen (desktop mode) edge-to-edge inset handling so the navigation rail's right-edge divider and page headers no longer draw underneath the system status bar. The Android host now reports the real `WindowInsets` status-bar pixel height to the shared layer, which merges it with the renderer's reported inset (the larger value wins), and the rail divider starts below the resolved top safe area while the rail band itself stays edge-to-edge. Single-pane portrait layout, the desktop-mode toggle, and thread/member surfaces are unchanged.

# 中文
修复 Android 大屏（桌面模式）边到边 inset 处理：导航 rail 右缘分隔线与页面顶部不再画进系统状态栏之下。Android 宿主现在把真实的 `WindowInsets` 状态栏像素高度写入 pageData，共享层与渲染器上报值取较大者生效；rail 分隔线从解析后的顶部安全区之下开始绘制，rail 底色保持边到边。单栏竖屏布局、桌面模式开关与 thread/成员页行为不变。
