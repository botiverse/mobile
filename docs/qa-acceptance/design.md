# 验收系统本体设计稿 v1（task #4）

作者：@Argus · 2026-07-16 · 状态：已合并 @Vera / @XiShi 评审意见（两票通过）；§8 待 @artin 拍板；§4b 契约字段等 task #2 首跑 verdict.json 实物最终锁定

> 依据：botiverse/hands 仓库实读 + #proj-quality thread 共识 + task #3 case schema v0.1 + task #2 PoC 评审意见。
> v0.1 → v1 变更：判定规则统一版（§6）、flaky=2 轮（§5）、assertion_results_json（§3）、apk 下载语义（§3/§4b）、护栏下发（§4b）、exit code 映射（§4b）、证据通道分阶段（§2/§4b）、录屏移 M2（§2/§9）。

## 1. 定位与范围

验收系统 = hands 发布生命周期里的**证据化验收 gate**：

```
CI 构建 → hands draft release → 【验收 Run：设备实测 + 证据 + 判定】 → 人审 → publish
```

- 一次验收 **Run 的对象是一个 hands release_id**（draft）。
- 触发源：hands webhook（自动）或 owner 手动发起。
- 产物：逐 case 证据（截图/steps.jsonl/dump/logcat；录屏 M2）+ M3 初判 + 人工终审，写回 hands（advisory 起步）。
- 范围外（v1 不做）：性能基准、安全测试、iOS（platform 字段预留，先 Android）。

## 2. 系统形态

独立产品，与 hands 同栈同模式，不塞进 hands：

- **Worker（Hono, Cloudflare）**：API + 静态 admin SPA + Login with Raft（搬 hands raft_accounts/raft_sessions/JWT 模式）
- **D1**：元数据（§3）
- **R2**：证据文件，key 规约 `evidence/{run_id}/{run_case_id}/{seq}-{kind}.{ext}`
  - **证据通道分阶段**：MVP-0 用 GitHub artifact（无 Worker）；MVP-1 起 executor 直传 R2（claim 后按文件领预签 PUT）——不走 artifact 中转（多一跳、90 天保留期/10GB 配额耦合、需搬运者）
  - **录屏 M2 再上**（screenrecord 3 分钟分段/模拟器抢 CPU）；MVP 证据链 = 逐步截图 + steps.jsonl + dump + logcat，R2 TTL 30 天可配
- **执行层不在本系统内**：executor = CI 一次性 headless 进程（GitHub Actions job），经 Agent API 交互
- **认证**：人走 Login with Raft；executor/CI 走 deploy token（搬 hands app_deploy_tokens）

## 3. 数据模型（D1 schema）

```sql
-- 结构化验收用例。steps/assertions JSON schema = task #3（Vera）
CREATE TABLE cases (
  id            TEXT PRIMARY KEY,
  app_slug      TEXT NOT NULL,            -- 对应 hands apps.slug
  title         TEXT NOT NULL,
  tier          TEXT NOT NULL,            -- 'L1'|'L2'|'L3'（护栏参数随 tier）
  device_spec   TEXT NOT NULL DEFAULT 'emulator',  -- 'emulator' | 'real-device'
  spec_yaml     TEXT NOT NULL,            -- case YAML：steps=goal+hints（agent 自主），assertions=结构化硬断言（机器可判）
  revision      INTEGER NOT NULL DEFAULT 1,
  baseline_ref  TEXT,                     -- 视觉基线引用（防 case 腐烂）
  max_retries   INTEGER NOT NULL DEFAULT 1,  -- 统一：1（共 2 轮）
  enabled       INTEGER NOT NULL DEFAULT 1,
  created_by    TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE case_revisions (
  id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES cases(id),
  revision INTEGER NOT NULL, spec_yaml TEXT NOT NULL, created_at INTEGER NOT NULL,
  UNIQUE(case_id, revision)
);

CREATE TABLE plans (
  id TEXT PRIMARY KEY, app_slug TEXT NOT NULL, channel TEXT NOT NULL,
  title TEXT NOT NULL, case_ids_json TEXT NOT NULL DEFAULT '[]',
  auto_trigger INTEGER NOT NULL DEFAULT 0,
  created_by TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE runs (
  id TEXT PRIMARY KEY,
  plan_id TEXT REFERENCES plans(id),
  app_slug TEXT NOT NULL,
  hands_release_id TEXT,
  apk_ref TEXT NOT NULL,                  -- 必须是"下载"不是"构建"：hands draft 签名下载 URL。
                                          -- 语义要求：被验收的必须是 draft 的原 artifact，重建产物无验收效力
  status TEXT NOT NULL DEFAULT 'pending',
  trigger TEXT NOT NULL,                  -- 'webhook'|'manual'|'schedule'
  requested_by TEXT NOT NULL,             -- 发起者（Raft 身份）= 终审 reviewer
  raft_task_ref TEXT,
  started_at INTEGER, finished_at INTEGER, created_at INTEGER NOT NULL
);

CREATE TABLE run_cases (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES runs(id),
  case_id TEXT NOT NULL, case_revision INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending', -- pending|running|judging|passed|failed|error|flaky
  attempt INTEGER NOT NULL DEFAULT 1,     -- ≤2（max_retries=1）；两轮结果都留档
  ci_job_ref TEXT,                        -- executor claim 时自报（GITHUB_RUN_ID/URL）；
                                          -- workflow_dispatch 无可靠 dispatch→run 关联，dispatcher 侧对不上
  verdict_json TEXT,                      -- executor verdict 原样（含 m3_judgments[]/m3_consensus，
                                          -- 契约 = Vera Verdict JSON，字段以 task #2 首跑实物锁定）
  assertion_results_json TEXT,            -- 断言级 breakdown（machine 判 / M3 判分开标记），reviewer 免解析全文
  human_verdict TEXT,                     -- NULL|'pass'|'fail'
  started_at INTEGER, finished_at INTEGER
);

CREATE TABLE evidence (
  id TEXT PRIMARY KEY, run_case_id TEXT NOT NULL REFERENCES run_cases(id),
  kind TEXT NOT NULL,                     -- 'screenshot'|'steps_log'|'ui_dump'|'logcat'|'report'|'recording'(M2)
  seq INTEGER NOT NULL, step_index INTEGER,
  r2_key TEXT NOT NULL, size_bytes INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE reviews (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES runs(id),
  reviewer TEXT NOT NULL, verdict TEXT NOT NULL,  -- 'passed'|'failed'
  comment TEXT, created_at INTEGER NOT NULL
);

CREATE TABLE operation_logs (
  id TEXT PRIMARY KEY, action TEXT NOT NULL, actor TEXT NOT NULL,
  subject_id TEXT, detail_json TEXT, created_at INTEGER NOT NULL
);
```

## 4. API 面（三组）

### 4a. Owner/UI API（Login with Raft 会话）
- `POST/GET/PUT /api/cases`、`/api/plans` — CRUD
- `POST /api/runs` — 手动发起（app + plan/case 子集 + release_id/apk_ref）
- `GET /api/runs/:id` — 看板：逐 case 状态 + assertion breakdown + 证据签名 URL
- `POST /api/runs/:id/review` — 终审（passed/failed + comment）

### 4b. Executor/Agent API（deploy token）— 契约 v0
- `POST /api/agent/runs/:id/claim`
  - 响应下发：case spec_yaml + **apk 下载 URL（hands 签名 URL 优先）** + 测试账号 secret 名 + **护栏参数（max_turns/max_minutes，按 tier：L1≈20 turns / L3≈60）**
  - 请求携带：**ci_job_ref（GITHUB_RUN_ID/URL 自报）**
  - workflow 侧只留 backstop：job timeout-minutes（≈45）兜 runner 卡死
- `POST /api/agent/run-cases/:id/evidence` — MVP-1 起返回预签 PUT URL 直传 R2（带 sha256）；MVP-0 走 GitHub artifact，字段兼容迁移
- `POST /api/agent/run-cases/:id/verdict` — 上报 verdict.json → judging
- **契约 v0 实物**（task #2 首跑 run 29487641099 锁定）：
  - 输入：`goal`（自然语言，含可验证要求）+ `max_turns`/`max_minutes`（claim 下发）
  - 输出 `verdict.json`：`{goal, verdict: pass|fail|error, reason, turns, durationMs, model, screen}`
  - 证据：`step-NN.png`（每 turn）+ `steps.jsonl`（逐 turn action+feedback，凭据脱敏）+ `agent-rc.txt`
  - **case-mode 增补字段**（对接 Vera schema，v1 契约）：`case_id`+`case_revision`、`assertions[]`（→ assertion_results_json）、`m3_judgments[]`/`m3_consensus`（面板落地后）、evidence manifest（文件名+sha256，直传 R2 校验用）
  - goal-mode 与 case-mode 共存：L3 探索 case 本身就是 goal 形态
- **exit code ↔ 状态映射**：`0`=pass→judging；`1`=fail→judging；`2`=无结论/基础设施（模型连败、模拟器不起、turn 超限）→error（可重派，不计判定统计）
- 执行顺序（成本短路）：**hard assertions 先跑（机器判，零模型成本）→ 全过才触发 M3 看 soft/visual**；hard fail 直接送审不烧 M3

### 4c. 集成 API
- `POST /api/hooks/hands` — 接 hands webhook（HMAC 校验，hands 侧已有签名+重试）。auto_trigger plan 命中 → 建 Run → dispatch
- **dispatch 到 CI**：Worker 调 GitHub `workflow_dispatch`（inputs: run_case_id + agent api base + token ref），每 job 一个 case、并发 = N job（前提 = apk 下载模式，acceptance job ~10min 无构建）
- **写回 hands（gate）**：Run 终审后在 release 上留验收结论，advisory 起步

## 5. Run 状态机

```
pending → dispatching → running → judging → in_review → passed | failed
                            ↘ error（exit 2：基础设施，可重派，不计判定）
run_case: fail(初判) → attempt 2（max_retries=1，共 2 轮）
          两轮都 fail → 送审标记 fail ｜ 一 pass 一 fail → flaky（两轮证据都留档，单独列）
```

## 6. 判定层：不对称 M3 面板（统一版，Vera 提案 + 两票通过）

原则：**误判 pass 比误判 fail 危险一个量级；agent 永不自判通过；终审永远在发起者**。

| 情形 | 结果 |
|---|---|
| executor 自报 fail 或 ≥1/3 M3 票 fail | 送审（标 fail-suspect） |
| 3/3 M3 pass 且无 hard assertion fail | 送审（标 **likely-pass**） |
| 2/3 M3 pass | 送审（标 **disputed**，需仔细审） |
| 三票全不同 | flaky → 重跑 |

M3 的系统角色 = **过滤器 + 排序器**，不是裁判：任何可疑都逃不过，pass 需高度一致才标 likely-pass。
M3 = pi 分级模型第三档（pi-ai 调用），跑在 executor 同一 CI runner。

## 7. Raft task 集成

- Run 创建 → 对应频道发消息转 task，`raft_task_ref` 记录
- 状态推进 → task thread 发进度（逐 case 结果 + 证据缩略）
- 进入 in_review → task 置 in_review 并 @发起者
- 终审 → task done/reopen；fail case 自动开 bug 工单（搬 hands feedback_tickets 模型，路由 lane owner）

## 8. hands 侧待确认（@artin 拍板）

1. webhook 事件表（0017）无 draft created 事件 → 需加 `release:draft_created`（小改）？
2. draft release APK 能否出签名下载 URL 给 CI（§3 apk_ref 的前提，同 share 机制应可行）
3. 验收结论写回落点：release 备注 / 新增 acceptance 字段 / 外链——advisory 阶段建议外链最省
4. palm real-device（L2）：device provider 抽象在 executor 侧，系统只存 device_spec——确认这个分层

## 9. 落地切法

- **MVP-0（本周）**：无 Worker——task #2 PoC + task #3 的 5 个 case，Raft task 当壳，证据走 GitHub artifact，人肉闭环验证契约
- **MVP-1**：Worker + D1 + R2（证据直传）+ Agent API + hands webhook + 最小 review 页（Run 看板 + assertion breakdown + 终审按钮）
- **M2**：录屏（分段拼接）、palm L2 lane、夜间回归 schedule、flaky 统计、bug 工单自动路由、blocking gate（main 必须 pass）
