---
name: Release
about: One issue per mobile version, the release record from cut to announcement
title: "Release X.Y.Z"
labels: release
---

发版记录：Raft Mobile **X.Y.Z**。每一步做完就勾掉并贴链接（PR、run、Hands release、回执），全部发完后关闭。

## 范围
- 源码：`release/X.Y.Z` @ `<commit>`（从 main 的 `<commit>` 切）
- 本版带的 PR：
- 没进本版、放下一版：
- 打包环境：安卓 Blacksmith；iOS Xcode `<版本>`（`<runner>`）

## 在途 PR
| PR | 内容 | 状态 | 是否随版 |
|---|---|---|---|

## 包和版本号
| 渠道 | versionCode | Hands release / 构建 | 状态 |
|---|---|---|---|
| 安卓 preview | | | |
| 安卓 main | | | |
| Google Play 正式 | | | |
| iOS TestFlight 内部组 | | | |
| iOS TestFlight 外部组 / App Store | | | |

## 冒烟
- [ ] 安卓（谁、在哪台设备、结果链接）
- [ ] iOS（谁、在哪台设备、结果链接）

## 发布
- [ ] 版本说明定稿（中英日；Play 每种语言 ≤500 字）
- [ ] 安卓 preview / main（finalize）
- [ ] Google Play 正式轨道
- [ ] iOS TestFlight 内部组 → 外部组 → App Store
- [ ] tag `vX.Y.Z` + GitHub Release
- [ ] 公告
