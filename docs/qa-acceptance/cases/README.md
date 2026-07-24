# QA Acceptance Case Library

> Structured acceptance test cases for Raft Android. Each case is a YAML
> file describing steps, assertions, and expected outcomes that an M3
> executor agent runs on a real device or emulator.

## Case Index

| # | ID | Title | Tier | Tags | Source |
|---|-----|-------|------|------|--------|
| 01 | `login-email-password` | 登录-邮箱密码登录 | L1 | auth, critical | Core path |
| 02 | `send-message-in-channel` | 发送消息-频道内发送文本消息 | L1 | messaging, critical | Core path |
| 03 | `channel-switch-navigation` | 频道切换-在频道间导航 | L1 | navigation | Core path |
| 04 | `task-flow-create-and-claim` | 任务流-创建任务 | L1 | tasks, workflow | Core path |
| 05 | `settings-open-and-navigate` | 设置页-打开设置并浏览子页面 | L1 | settings, navigation | Core path |
| 06 | `settings-feedback-submit` | 反馈上传-设置页提交反馈 | L1 | feedback, hands-integration | artin request |
| 07 | `thread-reply-and-reactions` | 消息回复-在thread中回复消息 | L1 | messaging, thread | Core path |
| 08 | `member-profile-open` | 成员资料-点击成员头像打开Profile | L1 | members, profile, regression | Hands feedback 3c1d6a34 |
| 09 | `image-preview-in-thread` | 图片预览-对话流中查看图片 | L1 | messaging, image, P0 | Hands feedback 0ec255d7 |
| 10 | `dm-open-and-send` | 私信-打开DM并发送消息 | L1 | messaging, dm, regression | Hands #1068, f96c5708 |

## Schema

See `case-schema.md` for the full case schema specification (v0.2).

Key design principles:
- **Steps are goal-oriented**: agent autonomously locates elements via
  uiautomator XML text matching + bounds calculation. Case provides
  natural-language hints, not hard selectors.
- **Assertions are typed**: hard assertions (text_present, text_absent,
  no_crash, logcat_clean) are machine-judged at zero model cost. Soft
  assertions (visual_check) are M3-judged.
- **Asymmetric judgment**: fail if any M3 panelist is suspicious;
  pass requires 3/3 consensus + no hard-fail.

## Android 1.7.0 Release Set

`release-1.7.0-android.json` is the fail-closed source of truth for the 26
canonical case IDs. The executable release subset contains 25 cases; each
requires `auth_fixture: alpha-qa` and
`launch_route: compose_slock_app_shell`, so Stamp freezes canonical bootstrap
instead of the legacy credential-receipt path.

`server-create` is the sole excluded case. It is manual-only provisioning with
retained server side effects (`scheduled: false`, `auth_fixture: none`) and
must never be added to an automated release plan merely to reach 26 cells.
Run `python3 scripts/qa/check_release_acceptance_cases.py` before publishing
case revisions. The check rejects missing/extra IDs, duplicate IDs, bootstrap
drift, or accidental admission of manual provisioning.

Automated plan creation must consume the same validated manifest through
`python3 scripts/qa/check_release_acceptance_cases.py --emit-plan`. Do not
construct a run by globbing all 26 YAML files: the emitted `case_ids` array is
the fail-closed 25-cell input and `server-create` appears only in the explicit
excluded list.

## Evidence Annotation

See `annotation-spec.md` for the sharp-based red-box highlight spec.
After screenshots, the executor draws colored boxes around assertion
regions so reviewers can instantly see what was verified.

## Executor Playbook

See `qa-acceptance-operations.md` (XiShi) for the executor experience
rules: keyboard handling, element location, navigation shortcuts, and
known failure modes learned from real runs.

## Adding New Cases

1. Copy an existing case as template
2. Fill in steps (goal-oriented), assertions (typed), and expected outcome
3. Tag with appropriate category (critical, regression, P0, etc.)
4. If from a Hands feedback ticket, reference the ticket ID in description
5. Test the case by running it through the executor workflow
