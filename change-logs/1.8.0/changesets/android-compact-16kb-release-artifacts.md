---
type: improved
area: release
platforms:
  - android
pull_request: 1683
rollout: full
---

## zh-CN

Android 安装包现在会压缩 DEX，并校验所有 64 位原生库的 16 KB 内存页兼容性，以减小下载体积并改善新设备上的安装和启动兼容性。

## en

The Android package now compresses DEX and verifies every 64-bit native library for 16 KB page-size compatibility, reducing download size and improving install and launch compatibility on newer devices.
