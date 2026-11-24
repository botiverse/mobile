---
type: fixed
area: diagnostics
platforms:
  - android
rollout: full
---

## zh-CN

过滤三星安装后 SPEG 预编译（`due to SPEG`）和小米预启动缓存回收（`PreStartCachedKill`）在无用户交互时产生的系统退出记录，避免将厂商后台维护误报为前台闪退或弹出后台保活提示。

## en

Filtered non-interactive OEM pre-launch and post-install maintenance exits on Android (Samsung `due to SPEG` profile generation and Xiaomi `PreStartCachedKill`) so they are no longer reported as foreground system kills or trigger background-kill notices.

## ja

Android でユーザー操作のないメーカー側の事前起動・インストール後メンテナンス終了（Samsung の `due to SPEG` および Xiaomi の `PreStartCachedKill`）を除外し、フォアグラウンド終了として誤報告されたりバックグラウンド終了案内が表示されたりしないようにしました。
