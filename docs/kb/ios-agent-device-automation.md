# Driving the iOS app with agent-device: sessions, runners, and the traps that cost a day

Owner: Jony · Verified at: 2026-10-18 · Status: current

`agent-device` is the external CLI suite this project uses to *act* on an iOS
app — tap, fill, swipe, orientation, snapshot the accessibility tree — the
things `xcrun simctl` cannot do (simctl has no public input API). This note
covers how the suite is put together, how it is exercised against the Raft
app, and the failure modes that have actually burned a QA session.

**Evidence level.** Command shapes below were re-verified against
`agent-device@0.20.10` on macOS arm64 with Xcode 26.1 simulators. Behaviour
notes marked "observed" come from real QA sessions on this project's sims;
they are empirical, not contractual — pin the version before trusting them.

## What the suite is

- An npm CLI. Run it as `npx --yes agent-device@0.20.10 ...` or install it
  (`npm install -g agent-device@0.20.10`). The version matters: session and
  runner behaviour has changed between releases; pin it in scripts.
- For iOS it does **not** inject events at the OS level. It builds and hosts
  an XCTest runner app (`AgentDeviceRunner`) on the target simulator or
  device, and drives the AUT through the XCTest accessibility surface.
  `snapshot` output is the AX tree; `press`/`fill`/`scroll` are XCTest
  actions. That is why a Kuikly-rendered screen shows up: the renderer
  exposes AX nodes.
- Commands go through a local **daemon** (one per `AGENT_DEVICE_STATE_DIR`,
  default `$HOME/.agent-device`). A daemon holds **sessions** (named,
  `--session <name>` or `AGENT_DEVICE_SESSION`), and a session holds a lease
  on a device runner. Stateful commands in one session are serialized; only
  read-only commands may be parallelized.

## How it is "built" and tested

- `agent-device doctor` reports device/app readiness.
- `agent-device prepare ios-runner --platform ios --timeout 240000` compiles
  (or reuses) the XCTest runner and health-checks it. Run this **after**
  boot/install and **before** replay/test in CI so first-use compile time
  does not land inside a measured step. It is a warm-up, not a repair.
- The suite's own test loop is the `open → snapshot -i → press --settle →
  verify → close` cycle described below; `replay`/`test`/`batch` run recorded
  flows. Product verdicts (did the *app* do the right thing) are never the
  suite's job — they belong to the caller's evidence.

## The working loop (the one that doesn't fight you)

1. `agent-device open <bundle-id> --platform ios --device "<name>" --session <name> --foreground`
   starts the session and returns an interactive snapshot with `@eN` refs.
   `open` without `--relaunch` is idempotent-foreground — it does not
   restart a running app. Do **not** probe with `devices`/`snapshot` first
   when you already know the app and device.
2. Act: `press <target> --settle`, `fill <target> "text" --settle`,
   `scroll <dir> --settle`, `back --settle`. `--settle` waits for UI quiet
   and prints the *diff* — continue from that diff; only run `snapshot -i`
   when the diff lacks your next target.
3. Targets: copy `@eN` refs exactly (with the `~sN` pin if shown). Refs go
   stale after any mutation. Selectors (`id=`, `role=`, `label=`, `text=`)
   are the fallback; coordinates are the last resort after a sparse/
   AX-unavailable snapshot, and then take a `screenshot` first and press
   pixel coordinates on the changed screen.
4. Verify with `wait text "..."`, `is`, `get`, or `find` — a bare screenshot
   or "the command succeeded" is not verification.
5. `agent-device close` ends the session.

Text entry: `fill` *replaces*, `type` appends to the focused field and never
takes `--settle`. `fill <target> ""` is **not** a clear command — an empty
replacement is rejected; use a visible clear control or report the gap.
`fill`/`press` on an ambiguous selector fails `AMBIGUOUS_MATCH` with a
candidate list; narrow with `role`/`id` or a printed ref, never geometry.

## Sessions, daemons, leases — the parts that bite

- **Sessions are global, not per-shell.** Any process with the session name
  reaches the same state. Name sessions deliberately (`myrole-taskid-date`)
  and never guess a device name — run `agent-device devices --platform ios`.
- **Ownership is a lease, not a suggestion.** If a live daemon owns the
  runner you get `"already owned by another agent-device daemon"`. That is a
  real conflict — do not treat `prepare`/`open` retries as the fix; find or
  stop the owning daemon (`daemon stop` on the host that owns it). A stale
  lease (dead owner, deleted state dir) is reclaimed automatically.
- **Do not re-open to "resume".** An existing session is continued by
  issuing the next command against it. Re-`open`ing a live session restarts
  state you may have needed (and on iOS collapses to
  `simctl launch --terminate-running-process` only when `--relaunch`).
- **Idle lifecycle**: `close` keeps a healthy iOS runner warm (~5 min) so the
  next `open` skips the runner build; the daemon itself self-exits after an
  idle window once it has no sessions. Overrides:
  `AGENT_DEVICE_IOS_RUNNER_IDLE_STOP_MS`, `AGENT_DEVICE_DAEMON_IDLE_TIMEOUT_MS`.
- **Observed trap**: an `open` that timed out (~90 s) reset the global
  daemon and *lost the session IDs* while the app/data survived. The working
  recovery was `prepare ios-runner` on the same device+session, then
  `open <bundle> --foreground` — do not blindly repeat `open`/reinstall.

## iOS specifics that differ from the docs' default assumptions

- `orientation` takes `portrait | portrait-upside-down | landscape-left |
  landscape-right`.
- The screenshot pixel buffer is **sensor-oriented**: on iPad it may stay
  portrait-sized while the app content is rotated. Do not derive orientation
  from PNG dimensions; check `snapshot` landmarks instead.
- iOS interactive-pop (swipe-back) is a **coordinate swipe**, not
  `gesture swipe left-edge` — the system edge gesture is not reachable via
  the semantic gesture API on every build. Same rule for tab-bar/nav-bar
  zones: pick press points away from edges and the home indicator.
- `open --launch-args <arg>` forwards process args;
  `open --launch-console <path>` captures the initial stdout/stderr of an
  iOS simulator launch — useful when the app prints a readiness marker
  (see [ios-fixture-launch-environment.md](ios-fixture-launch-environment.md)).
- A bare `open <app>` with no `--device` resolves "the sole running app on
  the sole booted simulator"; with multiple sims booted, always pass
  `--device`.

## Physical iOS devices

Physical hardware adds a signing gate, because `AgentDeviceRunner` is a real
XCTest bundle that must be codesigned:

```bash
export AGENT_DEVICE_IOS_TEAM_ID=<10-char Team ID>
export AGENT_DEVICE_IOS_BUNDLE_ID=com.<you>.agentdevice.runner
# only if Xcode can't pick a profile / a specific identity is demanded:
export AGENT_DEVICE_IOS_PROVISIONING_PROFILE=<profile name>
export AGENT_DEVICE_IOS_SIGNING_IDENTITY=<identity>
security find-identity -v -p codesigning   # discover team ids / identities
```

Devices on CoreDevice (modern, visible to `devicectl`) get the full command
set; older `xctrace`-only devices fall back to a reduced XCTest backend (no
install/logs/perf/deep-link). Keep the device paired, trusted, unlocked, on
cable, with Developer Mode on. First-run signing/setup takes visibly longer
— use `--debug` before declaring a timeout.

## How ios-use consumes it

`tools/mobile-use` (`ios-use`) defaults its interactive commands
(`tap`, `tap-text`, `swipe`, `interact`) to agent-device and locates the
binary via `AGENT_DEVICE_BIN` or `PATH`. It synthesizes
`--session ios-use-<device-slug>`, maps `tapText` onto a
`press 'role="button" label="..." || label="..." || text="..."' --settle`
selector (retrying button-only on `AMBIGUOUS_MATCH`), converts normalized
taps to pixels using the current screenshot size, and maps directional
swipes to `gesture swipe` or explicit coordinate swipes. The older
`QaAcceptanceLoopTests` XCTest file protocol remains as
`--driver qa-loop`; prefer agent-device for new work — the qa-loop requires
the workspace and a persistent test process.

## Safety rules that are policy, not etiquette

- Never run agent-device against a device you did not provision for QA — a
  human's trial device or a teammate's sim is owned, not shared. A lease
  conflict error is the floor of the protection, not the whole of it.
- Session logs can contain anything typed — including credentials. Never
  paste raw `agent-device`/`runner.log`/`daemon.log` into tickets or chat;
  sanitize first. Credentials for QA accounts live in private credential
  storage, never in evidence bundles.
- `install`/`reinstall`/`settings` mutate the device. On a shared Mac, an
  unqualified command is an unqualified blast radius — always scope by
  `--device` and `--session`.

## Related

- [ios-fixture-launch-environment.md](ios-fixture-launch-environment.md) —
  the env-injection contract this suite relies on for fixture runs.
- [ios-simulator-build-and-launch.md](ios-simulator-build-and-launch.md) —
  getting `build.raft.app` onto the simulator in the first place.
- `docs/mobile-device-automation.md` — the repo's cross-platform runbook
  (simctl/XCUITest path, shared reporting contract).
