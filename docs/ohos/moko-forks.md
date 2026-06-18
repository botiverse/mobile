# MOKO submodule fork migration spec

> **Status**: draft v0.6 (post @Cindy / @KMP-专家 review; "OHOS support" framing, moko-graphics dropped)
> **Owner**: @Pi-Kuikly-KMP专家
> **Reviewers**: @Cindy, @KMP-专家
> **Date**: 2026-06-18
> **Audience**: public — should not contain internal project names or task IDs

## 0. 目标

只对 `moko-resources` 走 bytemain fork（CPF-KMP-CMP 已经 fork 了 Slock 用的 0.26.0-OH，signal 强）。`moko-graphics` 保持上游，原因见 §1.2。

把为 OHOS + KBA 做的未提交改动推到 `bytemain/moko-resources` 的 `0.26.0-ohos` branch，避免 commit 进 upstream 主线。

把 `.gitmodules` 切到新 fork URL + branch，跟现有 `kuikly-ui` / `stately` / `sqliter` / `sqldelight` 四个 bytemain fork 的模式对齐。

## 1. 现状（`.gitmodules`）

```ini
[submodule "third_party/slock-source"]
    path = third_party/slock-source
    url = https://github.com/botiverse/slock.git

[submodule ".agents/skills/kuikly-ui-framework/references/KuiklyUI"]
    path = .agents/skills/kuikly-ui-framework/references/KuiklyUI
    url = https://github.com/Tencent-TDS/KuiklyUI.git

[submodule "third_party/stately"]
    path = third_party/stately
    url = git@github.com:bytemain/Stately.git
    branch = slock-2.1.0-ohos

[submodule "third_party/sqliter"]
    path = third_party/sqliter
    url = git@github.com:bytemain/SQLiter.git
    branch = slock-1.3.3-ohos

[submodule "third_party/sqldelight"]
    path = third_party/sqldelight
    url = git@github.com:bytemain/sqldelight.git
    branch = slock-2.1.0-ohos

[submodule "third_party/kuikly-ui"]
    path = third_party/kuikly-ui
    url = git@github.com:bytemain/KuiklyUI.git
    branch = staging

[submodule "third_party/moko-graphics"]
    path = third_party/moko-graphics
    url = https://github.com/icerockdev/moko-graphics.git
    branch = master   ← 保持上游，不 fork（见 §1.2）

[submodule "third_party/moko-resources"]
    path = third_party/moko-resources
    url = https://gitcode.com/CPF-KMP-CMP/moko-resources.git
    branch = main-0.26.0-OH  ← 第三方 fork，要建 bytemain fork
```

### 1.1 4 个已 fork 状态
`stately` / `sqliter` / `sqldelight` / `kuikly-ui` 都已在 bytemain org，带 OHOS 分支。

### 1.2 为什么 moko-graphics **不** fork

audit 结果（`git -C third_party/moko-graphics status -s`）：
```
?? graphics-build-logic/bin/
```
- 0 modified
- 0 deleted
- 0 staged
- 1 untracked：`graphics-build-logic/bin/`（build cache / 编译产物）

**结论**：Slock worktree 里"1000+ dirty"是 build cache 残留，**没有 source patch**。
- 走 `git clean -fd graphics-build-logic/bin/` 清理
- 不需要 fork repo
- 不需要 commit 到 bytemain

> 内部追溯：现有 bytemain 4 个 fork 的 `slock-*-ohos` 分支命名跟本 spec 不一致——本 spec 落地时也按"OHOS support"口径把**这 4 个** fork 的 branch name 一起重命名（`slock-2.1.0-ohos` → `2.1.0-ohos` 等），主仓 `.gitmodules` branch 字段同步更新。

## 2. 风险 / 边界

### 不在本 spec 范围内
- 改业务项目代码（moko API 集成）
- 改 `compose/shared/build.ohos.gradle.kts`（moco 依赖版本）
- 推动 moko 主线 PR（commit 在 fork 的 ohos branch 上，不污染 upstream）
- 重做 `moko-graphics` fork 跟业务项目实际用的 build 配置对齐

### 可能的下游
- 业务项目 i18n 链路会用到新 fork，但 owner gate 走
- 跟其它 in_review / in_progress task 独立

## 3. 步骤（只 moko-resources）

### Step 1：建 fork repo

```bash
# 命名沿用上游，bytemain org，public，跟现有 4 个 fork 一致
# description 不写业务项目名，写"OHOS support"
gh repo create bytemain/moko-resources --public \
  --description "MOKO Resources fork with OHOS support (0.26.0-OH)" \
  --homepage "https://github.com/bytemain/moko-resources"
```

**说明**：
- 仓库名 `moko-resources`，**不加**任何业务项目前缀
- description 简洁"fork with OHOS support (0.26.0-OH)"，不写 bytemain / 业务名
- `--public`：跟现有 bytemain fork 一致

Gate 1：repo 在 bytemain/ 下创建，public。

### Step 2：moko-resources 推到 bytemain

`third_party/.workdirs/moko-resources` 是对 `third_party/moko-resources` 的 worktree（如果不存在，按现有 submodule worktree 模式建）。

#### 2.1 pre-commit 过滤（避免 build cache 一起收）

```bash
cd third_party/.workdirs/moko-resources
# 1. 看 .gitignore 覆盖度
cat .gitignore | head -30
# 2. 看 ignored 文件分布
git status --short --ignored | head -50
# 3. 当前 repo size
du -sh .git/objects/pack 2>/dev/null
# 4. 检查 build cache 路径是否在 status 里
git status -s | grep -E '\.(gradle|cxx|idea|class|jar|kotlin_module)$|build/|^.. node_modules/'
# 5. 如果有 build cache 路径：
#    - 加到 .gitignore（如果上游没忽略）
#    - git rm -r --cached <path> + commit "ignore build cache"
#    - 再 commit OHOS patches
```

Gate 5：pre-commit 过滤检查通过，无 build cache 路径被误收。

#### 2.2 push OHOS support patches

```bash
git remote rename origin upstream
git remote add bytemain git@github.com:bytemain/moko-resources.git
git fetch upstream
git checkout -b 0.26.0-ohos upstream/main-0.26.0-OH

# 把 dirty 改动收 commit
git add -A
git commit -m "OHOS support patches for moko-resources 0.26.0-OH

Mirror the OHOS + KBA patches needed to support the Kuikly OHOS
render build path on top of moko-resources 0.26.0-OH.

Includes:
- OHOS Compose integration patches
- KBA (Kuikly Build Artifacts) build variant support
- moko-resources 0.26.0-OH API surface alignment"

git push bytemain 0.26.0-ohos --set-upstream
```

Gate 2：
- `git -C third_party/.workdirs/moko-resources status --short` 输出**空**（worktree 干净）
- `git -C third_party/.workdirs/moko-resources log bytemain/0.26.0-ohos --oneline | head -1` 不为空
- `git -C third_party/.workdirs/moko-resources diff upstream/main-0.26.0-OH...bytemain/0.26.0-ohos --stat` 输出**非空**
- 主仓 `git submodule status third_party/moko-resources` 指向 bytemain fork 上可解析的 commit

### Step 3：主仓 `.gitmodules` 切换

```diff
 [submodule "third_party/moko-resources"]
 	path = third_party/moko-resources
-	url = https://gitcode.com/CPF-KMP-CMP/moko-resources.git
-	branch = main-0.26.0-OH
+	url = git@github.com:bytemain/moko-resources.git
+	branch = 0.26.0-ohos
```

主仓提 commit：
```
chore(submodule): switch moko-resources to OHOS support fork

- third_party/moko-resources: CPF-KMP-CMP -> bytemain, branch main-0.26.0-OH -> 0.26.0-ohos

This puts the previously uncommitted OHOS support patches under
bytemain/ control (matching the pattern of KuiklyUI, Stately, SQLiter,
sqldelight) and removes the upstream / third-party direct dependency.
```

### Step 4：moko-graphics cleanup（**不**建 repo）

```bash
cd third_party/moko-graphics
# 1. 看 .gitignore 覆盖度
cat .gitignore | head -30
# 2. 检查 graphics-build-logic/bin/ 是否在 .gitignore
grep -E 'graphics-build-logic|/bin' .gitignore
# 3. 如果没在 .gitignore，加进去（可选；如果会反复污染 checkout）
echo 'graphics-build-logic/bin/' >> .gitignore
# 4. 清理本地 build cache（**不** commit，只 clean working tree）
git clean -fd graphics-build-logic/bin/
# 5. 验证 status 干净
git status -s
# 期望：空（或只剩 ignored 文件）
```

> 注：第 3 步（改 .gitignore）如果做，会让本地 `moko-graphics` 出现一个 modified .gitignore。**不** commit 到 bytemain（因为我们不建 graphics 的 bytemain fork），只在本主仓**记一笔**说明，或者干脆第 3 步跳过、只第 4 步本地 clean。

### Step 5：build 验证

按 moko 现行 build 链：
```bash
cd compose
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
| 1 | `gh repo view bytemain/moko-resources` | 返回 200，public |
| 2 | bytemain/moko-resources `0.26.0-ohos` branch | HEAD 存在，diff 含 OHOS support patches |
| 3 | moko-graphics worktree clean | `git status -s` 空（或只剩 ignored） |
| 4 | 5 个 gradle/hvigor 任务 | BUILD SUCCESSFUL，HAP 落地 |
| 5 | pre-commit 过滤 | moko-resources worktree status 干净，无 build cache 误收 |
| 6 | 主仓 submodule status | `moko-resources` 指向 bytemain fork 上可解析 commit |
| - | owner gate | **不**主动 push，等 owner |

## 5. 失败回滚

- Step 2 push 失败 → 留在本地 commit，fork repo 还在，retry 或 owner 介入
- Step 5 build 失败 → 主仓 `.gitmodules` + submodule pointer 回滚到旧 moko-resources；**新 fork repo 保留**（owner 复盘用）
- 删除 fork repo 只在以下任一情况：
  - 决定彻底切回 upstream（owner 决定）
  - fork repo 本身创建错了（名字 / description / 权限错）
- 默认**不**删 fork repo

## 6. Owner 路径

- 主导：@Pi-Kuikly-KMP专家
- Review：@Cindy、@KMP-专家
- 不需要外部权限介入（`gh` cli + bytemain org 已有 token scope）
