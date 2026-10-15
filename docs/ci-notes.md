# CI 踩坑指南（test.yml 统一预构建链路）

PR #65 把 test.yml 重构为「两个并行预构建 job 发布 fork 产物到 mavenLocal 缓存，
各平台 job 恢复消费」的架构。落地共踩了十余轮坑（含 OHOS host 门禁的容器内存墙），本文沉淀所有坑位与结论，
供后续改 CI 的人（和 agent）避雷。

## 架构现状

```
kuikly-artifacts (ubuntu) ──┐        fork-deps (ubuntu) ──┐
  android flavor 缓存        │          stately/sqliter/    │
  ohos flavor 缓存（分开！）  │          sqldelight/ktor 缓存 │
                            ▼                              ▼
        android (ubuntu)   ohos (macos)   ohos-host (容器)   ios (macos, 自建)
```

- 缓存 key 不带 OS（产物是 jar/klib/.module，平台无关），iOS 例外：apple klib
  只能在 mac 上发布，所以 ios job 自建、用 Darwin key。
- kuikly 的 android 与 ohos 两个 flavor 发布**重叠坐标**（ohos 过程会改写
  :core/:compose 的 common metadata），必须分两个缓存条目，消费端只恢复自己
  需要的 flavor，绝不能混在一个目录里。

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
kotlin-native-prebuilt、sqldelight 2.1.0-KBA-001）只在腾讯镜像上有。

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

## OHOS host 门禁（容器内存墙）

### 12. harmonyos-ci-image 容器缺的运行时依赖

`ghcr.io/bytemain/harmony-next-pipeline-docker/harmonyos-ci-image` 是 Ubuntu
基底但很精简，CI 里要现装：
- `zstd`——见坑 3（缓存压缩指纹）。
- `curl`——`scripts/prepare_ohos_sqlite_provider.sh` 用它下 SQLite amalgamation，
  没有会 `exit 127`。
（计划把这些预装进镜像，见 harmony-next-pipeline-docker。）

### 13. 编译器 JVM 调参：-Xss 与 daemon 选择

- Compose 编译器插件 `DeepCopyIrTreeWithRemappedComposableTypes` 按嵌套
  `@Composable` 递归，容器默认线程栈比 macOS 小 → 编 `:shared` 的
  ohosArm64 目标时 `StackOverflowError`。gradle.properties 全局加 `-Xss4m`
  修复；`-Dkotlin.daemon.jvm.options` 里多个参数要**逗号分隔**
  （`-Xmx4g,-Xss4m`），空格分隔会被当成一个非法 -Xmx 值导致 daemon 起不来。
- **konanc 的堆/栈两难（这就是 assembleHap 放不进小容器的原因）**：整程序
  release `-opt` 链接 libshared.so 极吃内存。konan **守护进程**有够大的线程栈
  （不 SOF）但堆是小默认值且**调不动**（`kotlin.native.jvmArgs` 无效）→ 堆 OOM；
  改用**进程内**（`kotlin.native.disableCompilerDaemon=true`）堆能用 `-Xmx` 调大，
  但深层 IR lowering 递归又爆 worker 线程栈（忽略 `-Xss`）→ SOF。debug 变体则
  过去从 `:shared` 直接产出动态库时，还会为全部 public UI/model/repository API
  生成超过 260 万行的 C adapter，单一 Clang translation unit 长时间占满链接阶段。
  现在动态库由 `:ohosBridge` 产出：它内部依赖 shared KLIB，但 C ABI 只暴露
  `raftInitKuikly`，实时接口继续走 KNOI；release workflow 同时限制生成 header
  不得超过 500 行。不要把 binary target 移回 `:shared`。

### 14. 容器资源与门禁范围

harmonyos-ci-image runner ≈ **7.8 GiB / 2 CPU，无 memory cgroup 上限**。这放不下
libshared.so 的 native 链接（见坑 14），所以 ohos-host 门禁**只做 ArkTS/.ets 编译**
（`ohpm install` → `hvigorw --sync` → `default@CompileArkTS`），拦住 host 侧 .ets
编译错（如 tabbar 那类改动）。libshared 的 Kotlin 内容已由「OHOS shared compile」
job 覆盖，所以放弃完整 unsigned-HAP 不丢覆盖面。换更大 runner 或镜像加内存后，
可把 `assembleHap` 加回来（workflow 里留了注释）。
