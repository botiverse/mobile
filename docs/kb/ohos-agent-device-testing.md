tags: ohos, harmonyos, emulator, hdc, slot, uitest, install

# OHOS agent-device 测试：固定 slot、安装与回读、登录态保护

Owner: Ark · Verified at: 2026-10-18 · Status: current

面向在共享 Mac 上手动操作 OHOS 模拟器的 agent/工程师。基础的镜像/实例创建与输入 smoke 见 `docs/ohos-emulator-setup.md`；CI runner 的槽位契约见 `docs/ohos-self-hosted-runner.md`；本文讲平时手动验证循环里的操作纪律和踩过的坑。

## 1. 工具与标识符

```bash
DEVECO_HOME=/Applications/DevEco-Studio.app/Contents
HDC="$DEVECO_HOME/sdk/default/openharmony/toolchains/hdc"
EMULATOR="$DEVECO_HOME/tools/emulator/Emulator"
```

App 标识（以 `compose/ohosApp/AppScope/app.json5` 实际值为准）：

- bundle: `build.raft.mobile`
- module: `entry`
- ability: `EntryAbility`

模拟器实例存放在 `$HOME/.Huawei/Emulator/deployed/<实例名>/`。引用具体实例时写实例名，不要把个人绝对路径写进报告。

## 2. 固定 slot：serial / UUID / marker / 锁

共享 Mac 上可能有多个 agent 各自的模拟器实例在跑。隔离的最小单位是 **slot**，由四样东
西绑定：

- **serial**：固定的 loopback TCP 端点，如 `127.0.0.1:16013`。DevEco 模拟器端口范围 10000–16555。
- **实例 UUID**：模拟器实例的唯一标识，用于确认「这台就是你那台」，而不是恰好占了同一端口的别的实例。
- **设备内 marker**：写在设备 `/data/local/tmp/raft-runner-slot-id`，内容是 `slot名|UUID`。
- **slot 命名**：人可读的槽位名，如 `ark-probe-16013`，与端口对应。

仓库里的权威实现是 `scripts/ci/ohos-slot-guard.sh`。agent 工作区里的 `device.sh` 一类封装只是它的薄壳。子命令语义：

```bash
scripts/ci/ohos-slot-guard.sh verify    # 只核对：host user、marker、model、API、software
scripts/ci/ohos-slot-guard.sh acquire   # verify 通过后占锁（mkdir 原子锁，记录 owner）
trap 'scripts/ci/ohos-slot-guard.sh release' EXIT   # 释放锁；owner 不匹配会拒绝释放
scripts/ci/ohos-slot-guard.sh hdc shell param get const.ohos.apiversion   # 代理 hdc，先 verify 再执行
```

guard 依赖的环境变量：`OHOS_EXPECT_HOST_USER`、`OHOS_SLOT_ID`、`OHOS_SERIAL`、`OHOS_INSTANCE_UUID`、`OHOS_EXPECT_MODEL`、`OHOS_EXPECT_API`（可选 `OHOS_EXPECT_SOFTWARE_CONTAINS`）。拿到一台「新」设备时先跑 `verify`，输出里确认 serial/marker/model/api 全部对上再动手。

关键纪律：

- 所有 install / shell / file / 输入 / 清理命令都走 guard 的 `hdc` 子命令（即 `hdc -p -t "$OHOS_SERIAL"`），不要绕过 guard 直接 `hdc shell`。
- `hdc list targets` 出现多个 target **不是**循环操作所有 target 的许可；一次只操作你 acquire 的那一个。
- `[Empty]` 表示当前没有 target，不要把它当成「设备掉了」之外的信息。

## 3. 共享 HDC server 的注意事项

HDC server 是 **按 macOS 用户共享** 的：同一用户下所有 agent、所有模拟器实例都连同一个 server 进程。

- `hdc -p` 只是「跳过 server 启动、直连已有 server」，**不是**私有传输通道。它不构成 slot 间的隔离证据。
- **不要重启/杀掉 HDC server**（`hdc kill` / `hdc start -r`）。你的一次重启会打断同机其他 agent 正在跑的安装或截图。设备异常时先分层排查：进程在不在 → 端口听不听 → server RPC 通不通 → target 是 Connected 还是 Offline，各层独立取证，再决定动哪层。
- 历史教训：曾把「目标不在列表里」直接当成 HDC 崩溃，实际是模拟器进程自己退出了，server 一直活着。模拟器退出与 HDC 崩溃是两回事，证据要分开。

## 4. 模拟器进程管理

- 启动要**显式且防重**：先确认没有同实例的 Emulator 进程、端口未被占，再启动；启动器用 detached supervisor 托管并记录最终退出码，不做自动重试。模拟器 CLI 是前台进程，终端关了就没了。
- 停止用显式命令：`"$EMULATOR" -stop "<实例名>"`（可带私有实例路径）。一次性验证实例用完即停，userdata 保留在本地、不外传。
- 长期保留的「原始实例」（已登录真实账号的那台）与一次性验证实例分开：验证新包用独立实例，绝不在原始实例上做会动账号的操作。

## 5. 截图与 uitest 输入

优先用仓库封装 `scripts/slock_ohos_hdc.py`（JSON 输出，便于留证）：

```bash
python3 scripts/slock_ohos_hdc.py --target "$OHOS_SERIAL" get-app-state --prefix login --out "$OUT_DIR" --dump-layout
python3 scripts/slock_ohos_hdc.py --target "$OHOS_SERIAL" click 540 1200
python3 scripts/slock_ohos_hdc.py --target "$OHOS_SERIAL" swipe --direction up
python3 scripts/slock_ohos_hdc.py --target "$OHOS_SERIAL" press-key Back
python3 scripts/slock_ohos_hdc.py --target "$OHOS_SERIAL" input-help   # 目标镜像的 uitest 能力以它为准
```

底层对应：

- 截图：`hdc shell snapshot_display -f /data/local/tmp/x.png`（失败时回退 `-i 0`），再 `hdc file recv` 拉回，然后删设备侧临时文件。
- UI 树：`uitest dumpLayout -p /data/local/tmp/x.json`。点击坐标前先看 layout，不要盲猜坐标。
- 输入：`uitest uiInput click X Y` / `swipe x1 y1 x2 y2 ms` / `keyEvent <键>`。不同镜像支持度不同，失败就把 `input-help` 输出附进报告。

实测踩坑：

- **`uitest uiInput inputText` 是插入语义**，在光标处追加，不清空原内容。要替换输入框文本，先 `uitest uiInput keyEvent 2072 2017`（Ctrl+A 全选）再 `inputText`。曾因漏了全选导致拼接出非法 URL，那是一个 INVALID TEST INPUT，不是产品缺陷——报告里要把这类输入错误和产品失败区分开。
- 端口转发清理要看语义回执：`hdc fport rm` 需要把 `tcp:<port> tcp:<port>` 作为两个独立参数；合并成一个引号参数可能 exit 0 但实际没删。删除类操作后用 `hdc fport ls` 确认结果，不要只信退出码。

## 6. 安装、覆盖安装与版本回读

```bash
hdc -t "$OHOS_SERIAL" shell aa force-stop build.raft.mobile
hdc -t "$OHOS_SERIAL" install /path/to/entry-default-signed.hap
hdc -t "$OHOS_SERIAL" shell aa start -a EntryAbility -b build.raft.mobile
hdc -t "$OHOS_SERIAL" shell bm dump -n build.raft.mobile   # 回读 versionCode / versionName
```

- **安装后必须回读**：`bm dump` 的 versionCode/versionName 要与包元数据一致。安装输出「success」但版本不对的事真实发生过。
- **覆盖安装保留登录态**：同 bundle、versionCode 递增的包装上去，数据与登录会话保留。这是验证升级路径的默认做法。
- **降级拒绝**：OHOS 拒绝同版本或更低 versionCode 的覆盖安装。验证 PR 包时遇到过 VC 比设备上已装版本低被直接拒装的情况，只能用同 source_sha 重新派生更高 VC 的验证包。所以派验证包前先 `bm dump` 看设备上现在是什么 VC。
- versionCode 的段含义见 `ohos-build-and-release-verification.md`；`11400008` = 1.14.0 build 8。

## 7. 登录态保护原则

- **绝不 uninstall 已登录实例**。uninstall 会清掉账号会话，且不可逆。需要换包一律覆盖安装。
- 验证会话持久化用 `aa force-stop` + 冷启动（`aa start`），不要用 uninstall/reinstall 代替。
- 会话清除类结论只到「UI/冷重启级」：登录页回来了、旧会话没复活，这是 UI 层证据；不等于字节级 secret-store 擦除证明，报告里不要把话说满。

## 8. fixture / staging 与真实环境的边界

- **Never auth against fixture**：自建 loopback fixture 只用来验证版本门/网络门逻辑，探针请求必须确认没有携带 Authorization/Cookie；绝不在 fixture 环境输入真实凭据。
- 顺序纪律：fixture 用例在登出状态下做；做完恢复真实环境（真实 staging Web/API 地址）再进行真实登录验证。
- 真实登录用已授权的 QA 凭据，凭据只存私有 0600 文件，不进聊天、不进报告、不进截图。原始截图/录屏/UI 树可能含账号信息，只上传脱敏后的报告。
- staging 与 production 切换类用例的完整判据：切走→冷启动→留在登录页；切回→冷启动→旧会话不复活。
