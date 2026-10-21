# CI 踩坑指南（test.yml 统一预构建链路）

PR #65 把 test.yml 重构为「两个并行预构建 job 发布 fork 产物到 mavenLocal 缓存，
各平台 job 恢复消费」的架构。落地共踩了十余轮坑，本文沉淀所有坑位与结论，
供后续改 CI 的人（和 agent）避雷。

## 架构现状

> 2026-10 更新：`fork-deps`（#3031）与 `kuikly-artifacts` job 均已移除；本仓库只构建
> Android 与 iOS，SQLDelight 等依赖直接用上游版本，任何 job 都不再回填 `~/.m2`。
> 下文提到 fork-deps / mavenLocal / ohos flavor 缓存的条目是当年踩坑的历史记录，
> 结论仍适用于其它 actions/cache 用法。

```
plan (ubuntu，按改动路径选平台)
                            ▼
        android (ubuntu)   ios (macos, 自建)
                 ↑ 全部依赖直接从 Raft Artifacts / Maven Central 解析
```

历史架构（PR #65 ～ #3031）：

```
kuikly-artifacts (ubuntu) ──┐        fork-deps (ubuntu) ──┐
  android flavor 缓存        │          stately/sqliter/    │
  ohos flavor 缓存（分开！）  │          sqldelight/ktor 缓存 │
                            ▼                              ▼
        android (ubuntu)   ohos (macos)   ohos-host (容器)   ios (macos, 自建)
```

- 缓存 key 不带 OS（产物是 jar/klib/.module，平台无关），iOS 例外：apple klib
  只能在 mac 上发布，所以 ios job 自建、用 Darwin key。
- （历史）kuikly 的 android 与 ohos 两个 flavor 发布**重叠坐标**（ohos 过程会改写
  :core/:compose 的 common metadata），必须分两个缓存条目，消费端只恢复自己
  需要的 flavor，绝不能混在一个目录里。同理适用于任何发布重叠坐标的多 flavor 产物。

## actions/cache 的坑

### 1. 跨 OS / 跨容器恢复：路径按「相对 workspace」重新锚定

actions/cache 打 tar 时把路径记为**相对保存时 workspace** 的形式
（`~/.m2` 在 ubuntu = `/home/runner/.m2`，相对 `/home/runner/work/<repo>/<repo>`
即 `../../../.m2`），恢复时以**当前 workspace** 为锚展开：

| 恢复环境 | workspace | `../../../.m2` 落点 | JVM user.home |
|---|---|---|---|
| ubuntu | /home/runner/work/m/m | /home/runner/.m2 ✅ | /home/runner |
| macos | /Users/runner/work/m/m | /Users/runner/.m2 ✅ | /Users/runner |
| 容器 | /__w/m/m | **/.m2** ❌ | **/root**（passwd） |

容器 job 恢复后需要一个「搬运」步骤把 `/.m2` 拷到 JVM 真实 user.home。
搬运目标**必须问 java 要**（`java -XshowSettings:properties -version` 里的
`user.home`）：容器里 `$HOME=/github/home` 是 runner 注入的，而 JVM 的
user.home 走 passwd（root → `/root`），Gradle 的 `mavenLocal()` 只认后者。

### 2. `GRADLE_OPTS=-Duser.home=...` 压不住构建 JVM

即使 `--no-daemon`，Gradle 也会 fork 一个 single-use daemon 作为构建 JVM，
它**不继承**客户端 GRADLE_OPTS 里的 `-D` 系统属性。不要试图用它改 mavenLocal
位置，改用上面的搬运方案。

### 3. 容器缺 zstd = 缓存永远 miss

actions/cache 的条目指纹包含压缩工具。ubuntu 保存用 zstd，容器里没有 zstd 时
恢复端只会找 gzip 版条目 → 永远 miss 且不报错。容器 job 第一步
`apt-get install zstd`。

### 4. 缓存条目按 key 不可变，坏内容会永久投毒

fork-deps 曾把一个只含 ktor 的 2.6MB 空壳存进 key（正常 60MB+），后续所有
run 都命中它。修好发布逻辑不够，必须**删掉坏条目**：
`gh api -X DELETE "repos/<owner>/<repo>/actions/caches?key=<key>"`。
排查时先看条目大小：`gh api repos/<owner>/<repo>/actions/caches`。
为此发布后加了 marker 工件断言步骤（Verify published fork dependencies），
再也不允许半空缓存被保存。

## bash 的坑

### 5. `if fn; then` 会关掉函数体内的 errexit（沉默半途失败）

```bash
attempt() { step1; step2; step3; }
if attempt; then ...   # step1 失败后 step2/step3 照跑，返回值 = 最后一条命令
```

POSIX 规定：函数在 if/&&/|| 条件位执行时，整个函数体内 `set -e` 失效。
fork-deps 正是这样把 stately/sqliter/sqldelight 的失败吞掉、只发了 ktor 还
绿色退出的。**修法**：把尝试体写进独立脚本文件，用 `if bash script.sh; then`
循环——新进程里的 `set -euo pipefail` 完整生效。

## Gradle 仓库解析的坑

### 6. init script 里 `allprojects{repositories{...}}` 会废掉 settings 仓库

给 project 注入 project 级仓库后，Gradle（PREFER_PROJECT 默认模式）会**忽略**
该项目 settings 里 `dependencyResolutionManagement` 的仓库——我们的子模块全靠
它声明腾讯镜像，KBA-* 工件瞬间解析不到。init script 只能加
`settingsEvaluated { pluginManagement { repositories {...} } }` 这类兜底。

### 7. 仓库连接错误是致命的，不会顺延下一个仓库

Gradle 只在「该仓库确定没有此工件」时才查下一个仓库；连接超时/5xx 直接中断
构建。所以**镜像要放每个 repositories 块的末尾**：canonical 仓库先命中就永远
不会碰镜像，只有 KBA-* 这类独占工件才会查到镜像。maven.eazytec-cloud.com
从 hosted runner 访问极不稳定且无独占工件，已从 ktor 补丁中整体移除。

### 8. CN 镜像仍需重试兜底

mirrors.tencent.com 偶发抽风，fork-deps 的发布步骤保留 3 次重试（30/60/90s
退避）。KBA 工件（如 kotlin-gradle-plugin:2.0.21-KBA-010、
kotlin-native-prebuilt）只在腾讯镜像上有；sqldelight 2.1.0-KBA-001 曾经也是，
现在 SQLDelight 走 Raft Artifacts 的 2.1.0-KBA-003，与镜像无关。

## 其他

### 9. 容器 job 的 git 基础设施

- workspace 由 host runner 用户创建、步骤以容器用户跑 → 需要
  `git config --global --add safe.directory '*'`。
- 镜像 git 2.25 没有 `ls-tree --object-only`，取 gitlink sha 用
  `git rev-parse HEAD:third_party/<sub>`。

### 10. 磁盘

kuikly（konan）+ fork-deps 任一全量构建都能吃满 hosted runner 约 14GB 空闲
盘。预构建 job 首步清理不用的预装工具链（保留 Android SDK——kuikly 要用）。

### 11. Signed-off-by 策略

commit-policy 检查读取的是 git trailer 块 = 提交信息**最后一个段落**。
`Signed-off-by` 与其他 trailer（如 Co-Authored-By）必须在同一段落，中间不能
空行，否则前面的会被当成正文。
