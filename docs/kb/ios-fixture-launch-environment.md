# iOS fixture launches: the SIMCTL_CHILD_ env contract and what a fixture does not prove

Owner: Jony · Verified at: 2026-10-18 · Status: current

The app can be pointed at a deterministic page at launch — for the visual-
testing capture harness and for QA carriers — entirely through environment
variables. This note is the contract: what to set, how the app reads it, and
the three ways a "working" fixture misled someone anyway.

## The mechanism

`xcrun simctl launch` forwards environment variables to the child process
when they are prefixed `SIMCTL_CHILD_` (the prefix is stripped; the app sees
the remainder). The app side lives in `ContentView.visualFixtureLaunch()`:

- `SLOCK_VISUAL_FIXTURE_PAGE` — the Kuikly/Compose page name to render as
  the root, replacing the normal app shell.
- `SLOCK_VISUAL_FIXTURE_PAGE_DATA` — optional JSON object; parsed and passed
  as the page's pageData. Absent/invalid JSON falls back to
  `{"pageName": <name>}` — a silent fallback, so a quoting bug does not
  error, it quietly runs the default payload.
- On appear, the fixture root prints `SLOCK_VISUAL_FIXTURE_READY page=<name>`
  to stdout; the capture job reads it via `simctl launch --console-pty`
  (or `agent-device open --launch-console <path>`) before screenshotting, so
  the shot never catches the splash.

```bash
SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE=compose_slock_app_shell \
SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE_DATA='{"pageName":"compose_slock_app_shell", ...}' \
xcrun simctl launch --console-pty <UDID> build.raft.app > launch-console.log 2>&1 &
```

The same env path carries QA-only switches (e.g. race-mode flags) because
`SIMCTL_CHILD_*` is a generic env channel — the fixture page is simply the
consumer that exists in the binary on every build. Nothing about it requires
a special build.

## The app-shell pageData trap (observed, cost one full test cycle)

Flags that the *product* sets via app-shell pageData are **not** injected by
iOS for you. `p_mergeExtParamsWithOriditalParam` in
`KuiklyRenderViewController.m` adds only host constants (safe-area metrics,
time context) — it does not synthesize feature flags. So a launch like:

```bash
SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE_DATA='{"pageName":"compose_slock_app_shell"}'
```

produces a running app that *looks* real but behaves differently: any shared
gate keyed on a pageData flag (the adaptive workspace flag is the live
example) reads its default — off. Every downstream adoption/branch then
fails closed, and the failure mode is a plausible-sounding product reject,
not a launch error. A full day of `not_dual_pane` rejections traced to
exactly this: the flag arrives only from pageData, and nobody put it there.

**Rule**: when a fixture run must exercise a feature, enumerate the flags
the product host injects on the normal path (check the real app-shell
pageData source) and pass *all* of them explicitly. "It launched" and "the
feature is enabled" are independent facts.

## Three ways a fixture lies to you

1. **Silent JSON fallback.** A malformed `SLOCK_VISUAL_FIXTURE_PAGE_DATA`
   (single quotes vs double, shell-escaped brace) does not fail — it falls
   back to the default payload. Verify the READY marker's page *and* assert
   one flag-dependent behaviour before trusting the run.
2. **READY means rendered, not correct.** `SLOCK_VISUAL_FIXTURE_READY` fires
   on appear of the fixture root. It says the page mounted; it says nothing
   about pageData, auth, or feature gates. Same "availability is not
   verification" rule as the build doc.
3. **The env channel survives reinstall.** You can re-launch an installed
   build with different `SIMCTL_CHILD_` values without rebuilding — useful,
   but it means the binary under test is the *installed* one, which may not
   be the source you think. Pin `installed-identity` (source SHA from the
   artifact metadata) alongside every fixture verdict; a simulator PASS on
   the wrong binary is still a wrong-binary PASS.

## Related

- [ios-agent-device-automation.md](ios-agent-device-automation.md) — driving
  the app after the fixture launch (sessions, runner, input).
- [ios-simulator-build-and-launch.md](ios-simulator-build-and-launch.md) —
  the toolchain that produces the `.app` the launch consumes.
- `docs/mobile-device-automation.md` — the cross-platform runbook this env
  contract plugs into.
