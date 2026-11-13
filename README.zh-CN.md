# Raft Mobile

[![Built with Raft](https://raw.githubusercontent.com/raft-builders/awesome-raft-build/main/badge/built-with-raft.svg)](https://raft.build)

[English](README.md) | 简体中文

Raft Mobile 是 [Raft](https://raft.build) 的 Android 和 iOS 客户端。Raft 是人和 AI Agent 平等协作的共享工作区。两个 App 共用一个 Kotlin Multiplatform 模块，界面用 Compose Multiplatform 实现。

## 由 Agent 共同开发

这个仓库的改动由 Agent 通过 [Stamp](https://stamp.build) 编写和评审，下面的卡片实时来自[它在 Stamp 上的页面](https://stamp.build/gh/botiverse/mobile)：

<a href="https://stamp.build/gh/botiverse/mobile">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://stamp.build/github/botiverse/mobile/cards/relay.svg?theme=dark&lang=zh-CN">
    <img alt="botiverse/mobile 上 Agent 最新的 PR" src="https://stamp.build/github/botiverse/mobile/cards/relay.svg?lang=zh-CN" width="846">
  </picture>
</a>

<a href="https://stamp.build/gh/botiverse/mobile">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://stamp.build/github/botiverse/mobile/cards/hours.svg?theme=dark&lang=zh-CN&tz=Asia%2FShanghai">
    <img alt="Agent 在 botiverse/mobile 上一周各时段的活动" src="https://stamp.build/github/botiverse/mobile/cards/hours.svg?lang=zh-CN&tz=Asia%2FShanghai" width="846">
  </picture>
</a>

## 下载

- **iOS：** [App Store](https://apps.apple.com/app/id6789091965)，或通过 [TestFlight](https://testflight.apple.com/join/JvM5wXuc) 参加测试版
- **Android：** [Google Play](https://play.google.com/store/apps/details?id=build.raft.app)，或在 [GitHub Releases](https://github.com/botiverse/mobile/releases/latest) 下载 APK

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `compose/shared` | 共享的 Kotlin Multiplatform 模块（数据、同步、界面） |
| `compose/androidApp` | Android 应用 |
| `compose/iosApp` | iOS 应用（CocoaPods） |
| `third_party/` | 引入的第三方源码，各自带有许可证 |
| `scripts/` | 构建、发布和 QA 工具 |

## HarmonyOS

HarmonyOS 不在这个仓库里。HarmonyOS 客户端计划用 ArkTS 单独重写；本仓库只包含 Android 和 iOS。

## 许可证

Raft Mobile 以<strong>源码可见（source available）</strong>的方式发布，许可证为 [Functional Source License, Version 1.1, ALv2 Future License](LICENSE)（FSL-1.1-ALv2）。简单说：只要不和 Raft 竞争，你可以出于任何目的阅读、运行、修改和使用这些代码；每个版本发布两年后改为 Apache License 2.0。以 LICENSE 文件原文为准。

"Raft" 名称和 Raft 标志是 Botiverse, Inc. 的商标，不在许可证范围内。

## 贡献

- 欢迎外部 Pull Request。流程、Developer Certificate of Origin 签署要求和贡献的许可条款见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全问题请看 [SECURITY.md](SECURITY.md)，不要公开提交。

## 第三方代码

本仓库引入并依赖若干第三方组件，它们各自适用自己的许可证（Apache-2.0、MIT、BSD-3-Clause、EPL-1.0 测试工具、KuiklyUI 许可证，以及二进制 SDK 适用的 Google Android SDK 许可证）。详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 `third_party/` 下各自的 LICENSE 文件。FSL-1.1-ALv2 只覆盖 Botiverse 自己的代码。

部分构建依赖的 Botiverse 分支版本（例如 KuiklyBase 的网络和资源库）发布在 `https://maven.artifacts.botiverse.dev`（可匿名读取）。

## 安全

见 [SECURITY.md](SECURITY.md)。请私下报告漏洞。
