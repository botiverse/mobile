tags: ohos, harmonyos, release, signing, versioncode, artifact, provenance

# OHOS 构建与发布包验证：workflow、版本号与产物身份链

Owner: Ark · Verified at: 2026-10-18 · Status: current

怎么派一个 OHOS 验证包、怎么确认下载到的包就是要验证的那个包。workflow 自身的权威说明是 `docs/ohos-release-ci.md`；本文是操作与核验视角的补充。

## 1. ohos-release workflow

`.github/workflows/ohos-release.yml`，只接受手动 `workflow_dispatch`（PR 触发只校验 workflow 定义，不构建）。输入：

| 输入 | 含义 | 约束 |
|---|---|---|
| `source_sha` | 要构建的精确源码 SHA | 必须完整 40 位 hex；**只允许配 `publish_to_hands=false`**（自定义源码 = 仅验证包） |
| `publish_to_hands` | 是否发布 Hands 草稿 | 必填，默认 true；true 也只建 **draft**，不进商店 |
| `version_major/minor/patch` | versionName 的 X.Y.Z | 非负整数 |
| `version_build` | versionCode 的 build 段 | **0–999**，见下面版本号公式 |
| `version_suffix` | versionName 后缀（如 rc1） | 留空为正式名 |

并发组 `ohos-release-${{ github.ref }}`、`cancel-in-progress: false`：同一 ref 上后派的 run 会等前一个自然结束，不要重复派。

实际踩过的坑：**`version_build` 传完整 versionCode 会被拒**。曾把 `11400007` 填进 `version_build`，resolve 作业直接失败：`version_build must be between 0 and 999`。1.14.0 的第 7 个构建应填 `version_build=7`，公式算出 `11400007`。

## 2. versionName / versionCode 组成

`scripts/ci/version_code.sh` 是三端唯一权威公式：

```
versionCode = major*10_000_000 + minor*100_000 + patch*1_000 + build
```

即 `1.14.0 build 8` → `11400008`。读 versionCode 时分段看：前两位段是 major.minor.patch，后三位是 build。build 域固定三位（0–999），溢出会被公式脚本拒绝——历史上两位 build 曾发生 build≥100 串位进 patch 段的别名问题，三位就是为堵这个。

## 3. 构建链里与验证相关的环节

按 job 顺序，验证时重点看：

1. **resolve**：日志里打印实际 `REQUESTED_SHA`、`PUBLISH_TO_HANDS` 和解析出的 `version_name/version_code`。派单时声称的源码不等于实际构建的源码，以这里的输出为准。
2. **signing-preflight**：签名 secret 齐全性检查（只有 CI 能接触签名材料，本机不做签名）。
3. **kuikly-artifacts**：`scripts/ci/verify_kuikly_release_manifest.sh` 校验不可变 Kuikly release set 完整。
4. **build-ohos-release**：链接 `:ohosBridge:linkReleaseSharedOhosArm64` → 拷 `libshared.so` 进 entry → `verify_kuikly_ohos_provenance.sh` → 打未签名 .app → hap-sign-tool 签名 → `scripts/validate_ohos_app_pack.sh` 解包校验 pack.info 的 bundleName/versionName/versionCode，并对 .app 和每个 .signed.hap 跑官方 `verify-app`。
5. 产物目录里写入 `source-identity.txt`（`workflow_sha` + `application_sha`）和 `ohos-release-metadata.json`，随 artifact 一起上传。

artifact 命名：`raft-ohos-<versionName>-<versionCode>`，内含签名 .app、独立 .hap、pack.info、metadata、source-identity、符号包。

## 4. 产物身份链核验（拿到包之后必做）

安装之前先核验，顺序：

1. **SHA-256 对账**：下载时校验归档哈希；解包后对 .hap/.app 重新 `shasum -a 256`，与 metadata/交接时声称的值比对。
2. **源码身份**：`source-identity.txt` 的 `application_sha` 与预期源码 SHA 一致；它与 workflow ref（`workflow_sha`）可以不同，以 application 为准。
3. **签名连续性与 profile**：用 hap-sign-tool `verify-app` 导出 cert/profile（工具在 `$DEVECO_SDK_HOME` 下，可用 `find "$DEVECO_SDK_HOME" -path '*/openharmony/toolchains/lib/hap-sign-tool.jar'` 定位），与上一个候选包导出的 cert/profile 字节比较，确认签名链没变。profile 是 release/app_gallery 类型——**它证明签名身份，不证明物理真机可装**。
4. **payload 对账**：需要时比对 unsigned/signed 包内条目（实测签名只增 `.pages.info` 之类签名文件，其余条目一致）。
5. **renderer 身份**：见下节。

只有这些全 PASS 才进安装环节；安装后再用 `bm dump` 回读版本闭环（见 `ohos-agent-device-testing.md`）。

## 5. HAR / Kuikly release set 与 packaged renderer

HAP 里的 Kuikly renderer 来自**不可变 release set**（HAR 形式钉在依赖里），不是源码现编。这带来一个反直觉的事实：

- **候选包的 versionName 涨了，不代表里面的 renderer 变了。** 实测 1.14.0/11400008 的 packaged renderer 与 11400006 字节级一致——上游 renderer 修复（当时的头像修复 PR）尚未进 release set，两个候选包都不含它。
- 要回答「这个修复在不在包里」，对账的是 renderer 的 HAR digest / .so SHA 与 release set manifest，不是 App 的 versionName。通用方法论见 `release-provenance-containment.md`。
- 验证某个 renderer 行为的 runtime 实验，需要先确认包里那个 `.so` 的身份，再谈 PASS 属于哪个版本。

## 6. 验证包 vs 发布

`publish_to_hands=false` 的包是纯验证载体：证明「这个源码在这个版本号下能构建、能签名、能在模拟器跑通这些用例」。它**不**构成发布动作、不激活 Hands、不进 AppGallery。发布侧的 draft 创建、商店动作是另外的授权面，验证回执里不要去暗示它们。
