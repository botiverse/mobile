# MOKO submodule fork migration spec

> **Status**: draft v0.7 (post owner gate: both `moko-graphics` and `moko-resources` go to bytemain fork)
> **Owner**: @Pi-Kuikly-KMP专家
> **Reviewers**: @Cindy, @KMP-专家, @Android-App-Integrator
> **Date**: 2026-06-18
> **Audience**: public — should not contain internal project names or task IDs

## 0. 目标

把仍指上游 / 第三方 fork 的 `moko-graphics` / `moko-resources` submodule **都**迁到 `bytemain/` org 的独立 fork：

- 命名沿用上游（`moko-graphics` / `moko-resources`，**不加**任何业务项目前缀）
- description 简洁"OHOS support"，不写 bytemain / 业务名 / Slock
- 分支不带 `slock-*` 前缀（用 `0.10.1-ohos` / `0.26.0-ohos`）
- 主仓 `.gitmodules` URL 切到 `git@github.com:bytemain/moko-{graphics,resources}.git`，branch 字段同步更新

跟现有 `kuikly-ui` / `stately` / `sqliter` / `sqldelight` 四个 bytemain fork 的模式对齐。

## 1. 现状（`.gitmodules`）

```ini
# moko-graphics submodule 被 commit c65e4e80（#157）删除了，
# 当前不在主仓 .gitmodules / gitlink 里。本 spec 落地时要重新添加。
# （moko-graphics 是被"先闪"了，不是"先 fork"，所以本 spec 是先 fork + 重新 add submodule）

[submodule "third_party/moko-resources"]
    path = third_party/moko-resources
    url = https://gitcode.com/CPF-KMP-CMP/moko-resources.git
    branch = main-0.26.0-OH
```

- `moko-graphics`：主仓**当前没有 submodule entry**（被 c65e4e80 删了）；本 spec 走 "bytemain fork + git submodule add" 一气呵成
- `moko-resources`：还有 submodule entry，指第三方 `CPF-KMP-CMP`；本 spec 走 "bytemain fork + 改 URL/branch"

`third_party/moko-resources` 当前 dirty 状态 owner gate 后补查（独立 audit）；`third_party/moko-graphics` 之前的 dirty 只是 `graphics-build-logic/bin/` build cache（§1.2）。

### 1.1 artifact 依赖事实

`scripts/prepare_moko_resources_ohos_kba.sh` 同时 publish 两套 KBA artifacts：

| 模块 | 版本 | artifact 路径 |
|---|---|---|
| moko-graphics | `0.10.1-KBA-010` | `dev.icerock.moko:graphics` + `graphics-ohosarm64` + `graphics-android*` |
| moko-resources | `0.26.0-KBA-010` | `dev.icerock.moko:resources` + `resources-ohosarm64` + `resources-android*` + `resources-generator` + `resources-compose*` |

`moko-resources` **本身**在 `resources/build.gradle.kts` 里有 `commonMainApi(libs.mokoGraphics)`，commonMain 源码直接 `import dev.icerock.moko.graphics.Color`。所以 graphics artifacts 是 resources 编译的前置依赖。

Kotlin KBA `2.0.21-KBA-010` ABI 跟 upstream moko-graphics `0.10.1` 不兼容，必须本地 patch + publish。

### 1.2 moko-graphics 当前 dirty audit

`git -C third_party/moko-graphics status -s`：
```
?? graphics-build-logic/bin/
```
- 0 modified
- 0 deleted
- 0 staged
- 1 untracked：`graphics-build-logic/bin/`（build cache / 编译产物）

**结论**：当前**没有** source patch，唯一 dirty 是 build cache。
- 不需要 commit source patch 到 bytemain fork
- fork 直接从 upstream pinned commit `c0e7ef83a243d17772a89be732ef64fb3463ed82` 开始
- KBA patches 仍然在 `prepare_moko_resources_ohos_kba.sh`（`patch_graphics` 函数）里 apply 到临时 workdir

> 内部追溯：现有 bytemain 4 个 fork 的 `slock-*-ohos` 分支命名跟本 spec 不一致——本 spec 落地时也按"OHOS support"口径把**这 4 个** fork 的 branch name 一起重命名（`slock-2.1.0-ohos` → `2.1.0-ohos` 等），主仓 `.gitmodules` branch 字段同步更新。

## 2. 风险 / 边界

### 不在本 spec 范围内
- 改业务项目代码（moko API 集成）
- 改 `compose/shared/build.ohos.gradle.kts`（moco 依赖版本）
- 推动 moko 主线 PR（commit 在 fork 的 ohos branch 上，不污染 upstream）
- 重做 KBA patch（patch 仍在 `prepare_moko_resources_ohos_kba.sh` 里）

### 可能的下游
- 业务项目 i18n 链路会用到新 fork，但 owner gate 走
- 跟其它 in_review / in_progress task 独立

## 3. 步骤

### Step 1：建 fork repo

```bash
# 命名沿用上游，bytemain org，public，跟现有 4 个 fork 一致
gh repo create bytemain/moko-graphics --public \
  --description "MOKO Graphics fork with OHOS support" \
  --homepage "https://github.com/bytemain/moko-graphics"

gh repo create bytemain/moko-resources --public \
  --description "MOKO Resources fork with OHOS support (0.26.0-OH)" \
  --homepage "https://github.com/bytemain/moko-resources"
```

**说明**：
- 仓库名 `moko-graphics` / `moko-resources`，**不加**任何业务项目前缀
- description 简洁"fork with OHOS support"，不写 bytemain / 业务名
- `--public`：跟现有 bytemain fork 一致

Gate 1：两个 repo 都在 bytemain/ 下创建，public。

### Step 2：moko-graphics 推到 bytemain

`third_party/.workdirs/moko-graphics` 是对 `third_party/moko-graphics` 的 worktree。

#### 2.1 pre-commit 过滤

```bash
cd third_party/.workdirs/moko-graphics
# 1. 看 .gitignore 覆盖度
cat .gitignore | head -30
# 2. 检查 graphics-build-logic/bin/ 是否在 .gitignore
grep -E 'graphics-build-logic|/bin' .gitignore
# 3. 如果没在 .gitignore，加进去（避免后续反复污染 checkout）
echo 'graphics-build-logic/bin/' >> .gitignore
# 4. 清理本地 build cache
git clean -fd graphics-build-logic/bin/
# 5. 验证 status 干净
git status -s
# 期望：空（如果 .gitignore 改动被算 modified，单独 commit "ignore build cache"）
```

Gate 5：pre-commit 过滤检查通过，无 build cache 路径被误收。

#### 2.2 push 到 bytemain

```bash
cd third_party/.workdirs/moko-graphics
git remote rename origin upstream
git remote add bytemain git@github.com:bytemain/moko-graphics.git
git fetch upstream
git checkout -b 0.10.1-ohos upstream/master

# 当前没有需要 commit 的 source patch，但 .gitignore 改动 / 清理注释可以加一个 commit
# 例如：
git add -A
git commit --allow-empty -m "OHOS support: pin upstream master + ignore build cache

Initial fork of icerockdev/moko-graphics to bytemain/moko-graphics
on the 0.10.1-ohos branch.

No source patches applied yet; KBA patches continue to live in
Slock's prepare_moko_resources_ohos_kba.sh and are applied to a
temporary workdir at build time.

This fork exists so future OHOS-specific patches can land here
without going to upstream."

git push bytemain 0.10.1-ohos --set-upstream
```

Gate 2：
- `git -C third_party/.workdirs/moko-graphics status --short` 输出**空**（worktree 干净）
- `git -C third_party/.workdirs/moko-graphics log bytemain/0.10.1-ohos --oneline | head -1` 不为空
- 主仓 `git submodule status third_party/moko-graphics` 指向 bytemain fork 上可解析的 commit

### Step 3：moko-resources 推到 bytemain

同 Step 2 模式：
- branch = `0.26.0-ohos`
- pre-commit 过滤同 2.1
- push 到 `bytemain/moko-resources`

Gate 3：同 Gate 2 模式，验证 bytemain/moko-resources `0.26.0-ohos` branch。

### Step 4：主仓 `.gitmodules` 切换

```diff
 [submodule "third_party/moko-graphics"]
 	path = third_party/moko-graphics
-	url = https://github.com/icerockdev/moko-graphics.git
-	branch = master
+	url = git@github.com:bytemain/moko-graphics.git
+	branch = 0.10.1-ohos

 [submodule "third_party/moko-resources"]
 	path = third_party/moko-resources
-	url = https://gitcode.com/CPF-KMP-CMP/moko-resources.git
-	branch = main-0.26.0-OH
+	url = git@github.com:bytemain/moko-resources.git
+	branch = 0.26.0-ohos
```

主仓提 commit：
```
chore(submodule): switch moko-graphics/moko-resources to OHOS support fork

- third_party/moko-graphics: icerockdev -> bytemain, branch master -> 0.10.1-ohos
- third_party/moko-resources: CPF-KMP-CMP -> bytemain, branch main-0.26.0-OH -> 0.26.0-ohos

This puts both MOKO forks under bytemain/ control (matching the pattern
of KuiklyUI, Stately, SQLiter, sqldelight) and removes the upstream /
third-party direct dependency.

No source patches moved — graphics dirty state was build cache only;
resources patches continue to live in prepare_moko_resources_ohos_kba.sh.
```

### Step 5：build 验证

按 moko 现行 build 链：
```bash
cd scripts && ./prepare_moko_resources_ohos_kba.sh --force
cd ../compose
./gradlew --no-daemon --console=plain :shared:testDebugUnitTest
./gradlew --no-daemon --console=plain :shared:compileKotlinOhosArm64
./gradlew --no-daemon --console=plain :androidApp:compileDebugKotlin

cd ../compose/ohosApp
ohpm install --all
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module -p module=entry@default -p product=default \
  -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

Gate 4：所有 build 通过，HAP 落地。

## 4. 验收 / 总结

| Gate | 检查项 | 期望 |
|---|---|---|
| 1 | `gh repo view bytemain/moko-graphics` / `bytemain/moko-resources` | 都返回 200，public |
| 2 | bytemain/moko-graphics `0.10.1-ohos` branch | HEAD 存在 |
| 3 | bytemain/moko-resources `0.26.0-ohos` branch | HEAD 存在 |
| 4 | 5 个 gradle/hvigor 任务 | BUILD SUCCESSFUL，HAP 落地 |
| 5 | pre-commit 过滤 | moko-graphics / moko-resources worktree status 干净，无 build cache 误收 |
| 6 | 主仓 submodule status | 两个 moko submodule 都指向 bytemain fork 上可解析 commit |
| - | owner gate | **不**主动 push，等 owner |

## 5. 失败回滚

- Step 2/3 push 失败 → 留在本地 commit，fork repo 还在，retry 或 owner 介入
- Step 5 build 失败 → 主仓 `.gitmodules` + submodule pointer 回滚到旧 moko-graphics/moko-resources；**新 fork repo 保留**（owner 复盘用）
- 删除 fork repo 只在以下任一情况：
  - 决定彻底切回 upstream（owner 决定）
  - fork repo 本身创建错了（名字 / description / 权限错）
- 默认**不**删 fork repo

## 6. Owner 路径

- 主导：@Pi-Kuikly-KMP专家
- Review：@Cindy、@KMP-专家、@Android-App-Integrator
- 不需要外部权限介入（`gh` cli + bytemain org 已有 token scope）