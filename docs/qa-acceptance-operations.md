# QA acceptance — operation playbook

How an executor agent drives the Raft app accurately on an emulator, distilled
from real acceptance runs (each pattern cites the run that taught it). Three
consumers keep this in sync:

- `scripts/qa/acceptance_agent.mjs` — the runner preamble encodes the DO rules.
- Case authors — the *hints* guidance tells you what a case must spell out.
- This file — the growing record. **After every failed round, add what the
  evidence taught** (that is the round-1 → round-2 loop that took login from
  fail to pass in one iteration; runs 29487641099 → 29488358034).

## Locating & tapping

- **Never eyeball coordinates from a screenshot.** Round-1 turns 1–4 tapped a
  point ~180px above the email field and typed into nothing. Always
  `dump_ui` → find the node by its `text` → tap the center of
  `bounds="[x1,y1][x2,y2]"`.
- **Kuikly nodes expose `text` and `bounds` only** — no `resource-id`, no
  `content-desc`. Text matching + bounds math is the primary locator; there is
  nothing else to bind to (case specs must not reference resource ids).
- **Icon-only controls have no text in the dump.** The case must carry a
  position hint ("send button at the right end of the composer row"); the agent
  combines the hint with the screenshot and neighboring text nodes' bounds.

## Message actions & links

- **Message-level actions (reply, forward, save, create/claim task) live
  behind `long_press` on the message row** — a plain tap opens/permalinks
  instead. The first 13-case batch round failed three cases waiting for a
  long-press the toolkit didn't have; it does now.
- **`open_link` dispatches a real Android VIEW intent** — use it for share
  links and deeplinks instead of typing a URL somewhere. It needs a full
  `scheme://...` URL.

## Text fields

- **Every `text` action's Result reports where the text landed** ("typed;
  focused field now contains ... at [x1,y1][x2,y2]") — read it. If the bounds
  belong to the WRONG field (batch-round recurrence: password text in the
  email field), tap the right field, `clear`, and redo before proceeding.
- Sequence: tap the field → **verify focus** (cursor visible in the next
  screenshot) → type → **verify the text landed in the intended field**.
  Round-1's core failure: a tap that didn't move focus, so the password was
  appended into the email field — and the agent only noticed via dump three
  turns later.
- **The keyboard relayouts the window.** Bounds captured before the IME opened
  are stale; `dump_ui` again after the keyboard appears, before tapping the
  next field. `esc` (keyevent 111) hides the keyboard; prefer it over `back`,
  which may navigate.
- **Clearing a field needs `clear`, not repeated `del`.** Ten single del
  presses in round-1 removed exactly ten characters and burned ten turns. The
  `clear` action jumps to the end (MOVE_END) and sends one adb call with a
  60×DEL burst.
- `input text` cannot carry shell metacharacters (runner rejects them) and
  encodes spaces as `%s` — keep acceptance inputs simple; credentials go in as
  `{{QA_EMAIL}}` / `{{QA_PASSWORD}}` placeholders, substituted only at adb
  time and redacted from all evidence.

## Timing

- The runner settles ~1.5s after every action, but page transitions and
  network round-trips (login, channel open) need an explicit `wait` of 2–3s —
  round-2 used `wait 3` after submit and after opening a channel.
- If a screen looks mid-loading, spend a turn on `wait` + fresh screenshot
  rather than acting on a half-rendered layout.

## Recovery

- App crashed or backgrounded → `launch` restarts the main activity.
- Wrong screen → one `back`, then re-orient from a fresh screenshot; don't
  chain blind backs.

## Verdict discipline

- **Verdict before cleanup.** channel-create's rerun verified every
  requirement, then burned its whole remaining budget hunting for a delete
  entry and timed out with no verdict (run 29510717612) — the same pattern
  killed the first edit/archive rounds. Finish the moment the requirements
  are proven; do cleanup best-effort afterwards and report failures in the
  reason rather than swallowing the verdict.
- **Menus are entrances, not leaves.** The whole team mislabeled Forward as
  platform-pending because the agent enumerated the long-press menu without
  opening "Select Message" — the Forward entry lives inside the multi-select
  mode. Descend one level into Select/multi-select/edit items before
  concluding a feature is missing.

- `pass` only after **every** requirement in the goal is visually confirmed —
  round-2's pass named each: credentials accepted, channel list rows seen,
  channel opened, message list rendered.
- `fail` with the concrete blocker, not a guess. Round-1's fail reason
  ("cannot clear email field, Sign In stays disabled") matched the screenshots
  exactly — an honest fail with evidence is a *good* run; it feeds this file.
- No exploration outside the goal: the turn budget is part of the contract.

## Writing cases that agents can hit

- State requirements as visually checkable facts ("a row whose text starts
  with `#`", not "the UI looks right").
- Give position hints for every icon-only control the flow touches.
- Name the surfaces by their on-screen text (the dump locator) — e.g. "the
  CHANNELS section", "the row `#all`" — that is what the agent can find.
- Expect the login state: cold-start lands on Sign In; a server picker may
  follow login (round-2 had to choose the server before Home).

## Stale bounds in dialogs — prefer tap_text

- Dialogs re-layout as state changes (a member row toggles selected, the
  keyboard opens, a validation hint appears): bounds you computed from an
  earlier dump_ui move out from under the tap. The edit-name 80-turn run
  burned 20+ turns blind-tapping "Create Channel" ~100px above the real
  button after the list shifted.
- Use `tap_text` for anything with a visible label — the runner re-resolves
  the element's bounds from a fresh dump at the instant of the tap. Reserve
  coordinate taps for unlabelled/icon-only targets, and re-dump right before
  those when inside a dialog.

## Smoke before product QA (pinned-bits / new-package runs)

Before running product acceptance cases against a package the harness
hasn't driven before — a pinned APK (`--apk-run`/`--apk`), an alpha/release
build, or after any workflow change — dispatch a cheap 5-turn smoke first
("confirm a Raft screen is visible, not the launcher"). A smoke failure is a
harness/infra defect, NOT a product verdict, and must not consume a real case
round. The 1.5.0 release-day pinned-bits path shipped four latent defects
that all surfaced in smoke, zero reaching product QA:

- cross-run artifact read needs `actions: read` (else HTTP 403);
- launch must derive package + activity from the APK (alpha is
  `build.raft.app.alpha`, not the hardcoded `build.raft.app`);
- build-info for a foreign run records `dispatch_ref` + aapt package/version,
  never the run's headSha-as-source_commit (`apk_sha256` is authoritative);
- the build-skip path removes the ~20min boot cushion, so wait for
  `sys.boot_completed` before `adb install` (wait-for-device returns on
  connect, not boot).

Rule: smoke green → product case. Smoke red → fix infra, re-smoke; never mark
the product case FAIL on a harness failure.
