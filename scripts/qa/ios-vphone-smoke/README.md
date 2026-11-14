# Raft iOS vphone smoke harness

Dependency-free Node `.mjs` automation for an existing vphone guest. The runner uses `vphone-launchpad-cli` argument arrays (never a shell), bounds process time/output, checks RPC envelopes, and captures accessibility trees plus full-resolution PNGs. It does not create a machine, build Raft, install Raft, or replace release/device review.

## Setup

- Use a current Node release with `node:test` support (Node 20 or newer).
- Install and configure vphone-launchpad, its helper, and a compatible `VPhone.bundle`. The guest must already expose the RPC methods used here (`device.info`, `apps.*`, `ui.*`, `input.*`, `clipboard.*`, `script.eval`, `files.plist`, bounded `logs.syslog`, and screenshot-to-file). Check an older guest with `methods.list` and update its environment through vphone-launchpad if necessary.
- Create/provision the machine separately. Set `VPHONE_MACHINE` to its exact existing name. This harness deliberately has no `vm create` path.
- Install the Raft build under test separately and set `QA_BUNDLE_ID` if it is not `build.raft.app`. Groups 11 and 12 reject builds whose `apps.info` marketing version is older than or missing 1.26.
- Use an English UI and a disposable QA account/workspace. The default targets are `OranixSlockTest` and `#all`; case 02 really posts `smoke test, please ignore` in the channel and its thread. Groups 11 and 12 also create QA-labelled messages when their fixture is absent.
- Safari text coverage opens `https://example.com/`, so that case needs guest network access. App sign-in and the QA realtime fixture API also need network access.
- Keep a signed-in session for the fastest startup. Startup checks the selected machine, guest device/app identity, foreground bundle, Home navigation, and exact QA server. A signed-in session on another server is reused after selecting the QA server. If signed out, both `QA_EMAIL` and `QA_PASSWORD` are required for browser sign-in. Case 10 always requires both because it signs out and relogs in; cases `12-realtime-offline` and `12-realtime-server-switch` require both for API fixture posts. Browser sign-in also handles the system `Continue` consent surface, which is not exposed in the guest AX tree, with one normalized raw-pixel tap followed by web AX assertions.
- The server-switch realtime case needs a second server available to the same QA account. It reports `NOT_COVERED` after recording the KMP transport and server-list evidence when no alternate exists.

`apps.install` is exposed by the driver for a separately requested provisioning flow, but the smoke runner does not invoke it. It re-signs/places the bundle and bypasses installd and profile checks. A successful `apps.install` or subsequent launch is **not TestFlight, installd, provisioning-profile, or distribution evidence**.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `VPHONE_CLI` | `vphone-launchpad-cli` | CLI executable or absolute path. |
| `VPHONE_MACHINE` | required | Existing machine to start/reuse; never a create request. |
| `QA_BUNDLE_ID` | `build.raft.app` | Installed Raft bundle identity to verify and launch. |
| `QA_SERVER_NAME` | `OranixSlockTest` | Exact server required after startup/relogin and for realtime fixtures. |
| `QA_CHANNEL` | `all` | QA channel name, with or without leading `#`. |
| `QA_ALT_CHANNEL` | `qa-test-create-58291` | Different visible channel used to prove cross-conversation realtime delivery. |
| `QA_API_BASE` | `https://api.raft.build/api` | QA API origin used only for realtime fixture login/server/channel lookup and message posts. Set the staging equivalent when using staging. |
| `QA_OUT` | `artifacts/ios-vphone-smoke/<timestamp>` | Evidence directory. Created outside the source directory at runtime. |
| `QA_EMAIL` | empty | Browser/fixture QA login email; required when login is needed, for case 10, and for realtime fixture posts. |
| `QA_PASSWORD` | empty | Browser/fixture QA password; same requirement as `QA_EMAIL`. |
| `QA_STOP_AFTER` | `false` | `1/true/yes` stops the selected VM in runner cleanup. |

Do not put credentials in `QA_OUT`, the command line, case IDs, or a checked-in file. Export them only in the replay shell/session.

## Run

From the repository root:

```bash
export VPHONE_MACHINE=raft-iphone
export QA_EMAIL='...'
export QA_PASSWORD='...'
node scripts/qa/ios-vphone-smoke/run-smoke.mjs
```

The runner executes the registry in order and exits nonzero if startup, cleanup, or any runnable case fails. `NOT_COVERED` alone does not make the run fail. To replay selected cases, append registry IDs; order remains the registry order:

```bash
node scripts/qa/ios-vphone-smoke/run-smoke.mjs 04-image-empty 04-image-text
```

Registry:

- `01-cold-start`: terminate/launch, measure milliseconds to Home and fail above 5000 ms, verify all four tabs, switch Activity/Tasks/Search/Home, pull to refresh, and require the loading state.
- `02-channel-thread`: post the exact smoke text, verify the long-press menu, reply in a thread, and verify the outer `1 reply` row and exact preview.
- `03-photos`: records a PhotoKit/screen environment probe and returns `NOT_COVERED`; no deterministic harness-owned photo fixture/authorization is claimed.
- `04-safari-text`: Copy the paragraph fragment containing `relying` in Safari, then verify composer paste through both long-press and Cmd+V.
- `04-image-empty` and `04-image-text`: independently seed an **image-only** guest UIPasteboard and test menu Paste plus Cmd+V with an empty composer and with existing text. Both forms require exactly one menu Paste, entry into the fixed `Edit image` editor, and preservation of existing text. These are expected to fail on the affected 1.25 build and must pass on 1.26; there is no XFAIL or version-based skip for #4779.
- `05-activity`: require an Activity row containing the exact message and visible task status, then open the exact message.
- `06-saved`: save (or reuse an already saved) exact message, open Home Saved, and reopen the original.
- `07-tasks`: switch Mine/All, apply Done, and open a Done task conversation.
- `09-ottie`: records the Ottie/home environment and returns `NOT_COVERED` because no controlled signed-in Ottie assistant session is available.
- `10-appearance-relogin`: capture Dark and Light, open the Feedback list, sign out with confirmation, and browser-relogin to the exact QA server/Home.
- `11-double-tap-replies`: use an existing reply fixture or create one, send an 80 ms AX-frame double tap to `N replies`, and prove one Back returns to the channel rather than a duplicate thread.
- `11-double-tap-avatar`: associate the nearest AX avatar button with the exact QA message, double-tap it, verify one profile destination, and prove one Back returns.
- `11-double-tap-link`: Copy Link on the QA message, post that internal link, double-tap its AX button, and prove one Back returns to the linking conversation.
- `12-realtime-connect`: require 1.26, explicitly enable Settings › Developer › New realtime connection (Beta), force a fresh connection, and require the latest `connect_start transport=kmp_socketio`, subsequent `kmp_socket_connected`, and `sync_resume_response` log markers.
- `12-realtime-offline`: switch en0 to a TEST-NET manual address, post through the host API while the guest is offline, restore DHCP in `finally`, and require the exact message plus fresh KMP connected/resume evidence.
- `12-realtime-channel-switch`: open `QA_ALT_CHANNEL`, post to `QA_CHANNEL` through the host API, return, and require the new message plus KMP evidence.
- `12-realtime-background`: keep Safari foreground for 10 seconds while the host posts, return to Raft, and require the message plus KMP evidence.
- `12-realtime-server-switch`: switch to an alternate server, post a QA message while away, switch back, and require the exact message plus KMP recovery evidence.

Independent later cases still run after a failure. Each case has its own timeout; timeout cancels active CLI child processes. A failed startup blocks runnable cases with `FAIL` and still emits the report. The runner clears the guest clipboard on a best-effort basis and optionally stops the VM. A 20-second `screen.unlock` keepalive prevents the reference guest's 60-second Auto-Lock from interrupting long replays; it is stopped during cleanup and does not change system settings.

## Evidence and credentials

`QA_OUT` contains:

- `summary.json`: public configuration, startup device/app/session facts, per-case status/duration/detail/evidence, counts, and overall verdict.
- `report.md`: the same three-state result in reviewable form.
- `<case-id>/`: named AX `*-tree.json` files, PNGs, and structured probes/attempt results.

The driver uses semantic `ui.tap_element` first and falls back to the center of a visible AX frame only when that RPC fails or declines. This is required for Compose controls whose AX frames do not respond reliably to coordinate taps. Input coordinates are screen **points**, not raw socket pixels. Some Compose popups expose only one merged AX button: server Settings derives its lower-middle action from that popup's frame, and task options use their AX frames directly. These coordinate actions still require destination/filter AX assertions. Composer long-press uses the left quarter of the field for 1.5 seconds, which produced the native Paste menu on the reference guest. The sign-out confirmation first uses a point-based `input.double_tap`; if the modal is unchanged after 4 seconds, it records that attempt and makes one raw-pixel tap. Both methods and any fallback are preserved as structured evidence. Evidence includes the observed state rather than treating an RPC `ok` as a product assertion.

Both the email and password are registered with the redactor. `secretType` writes each credential to a local 0600 file, pushes only that file path to the guest, reads it into UIPasteboard inside the guest, pastes with Cmd+V, then removes the file and clears the clipboard. Credentials never appear in CLI argv. The realtime fixture access token is registered as soon as it is issued. The redactor masks literal and URL-encoded occurrences in thrown errors, console output, JSON evidence, and Markdown. Structured `password`/token fields are also masked. Never print `env`, RPC login arguments, API authorization headers, or unredacted login trees. PNG capture is suppressed while an account/login screen may contain credentials; the redacted AX tree and a skip note remain. Do not add screenshots of populated credential fields. Evidence can still contain ordinary QA message content and should live outside Git.

The three states are strict:

- **PASS**: every assertion in that case ran successfully.
- **FAIL**: a product assertion failed, a prerequisite was absent, login/startup failed, evidence capture failed, or the harness timed out. Expected 1.25 image-paste failures, pre-1.26 group 11/12 builds, and legacy realtime transports remain `FAIL`.
- **NOT_COVERED**: the case ran an environment probe and recorded why this environment cannot support meaningful product coverage. It is neither a pass nor a product failure.

## 1.26 navigation and realtime evidence

Each double-tap case records source, destination, and returned AX trees/PNGs plus `*-single-open.json`. A visible destination alone is insufficient: the returned tree must be the original conversation after exactly one Back. If two destinations were stacked, that Back leaves the duplicate destination visible and the case fails.

Realtime evidence is intentionally stronger than a Developer toggle or a recovered message list. The harness analyzes the latest `connect_start` in bounded `logs.syslog` output and requires `transport=kmp_socketio`, followed by `kmp_socket_connected` and `sync_resume_response`. Initial connections and offline relaunches must also exceed the pre-restart connection generation, preventing historical logs from counting as fresh recovery. A later `swift_socketio` start therefore fails with `old_client_transport` even if older KMP lines or a visible message would otherwise look successful. The fixture token is never written to evidence. The Developer override is left enabled for subsequent realtime cases.

The offline case uses a **real guest interface change**: en0 is switched to manual `192.0.2.1` while preserving router/subnet evidence, the host API posts, and DHCP is restored in `finally`. It does not claim radio/airplane-mode or host-network outage coverage. The background case uses a real Safari foreground interval, and the channel-switch case uses `QA_ALT_CHANNEL`; neither is simulated by process termination. The server-switch case posts into the QA server while another server is selected and requires the message after returning. All fixture messages use unique `smoke realtime … [qa-…]` text only in the configured QA channel. Fixture routes and fields were checked against `botiverse/slock` commit `b0527e7cb6f798bef3dd09877ae13dfc977357c7`: `POST /auth/login`, `GET /servers`, inbox `groups[].channelId/channelName`, and `POST /messages` with `channelId/content` plus `X-Server-Id`. That pinned contract SHA is included in fixture evidence.

## Safari image-copy versus direct image seeding

Safari text Copy is exercised as a real browser-to-Raft path. The image regression uses `script.eval` with UIKit to put a deterministic decoded `UIImage` directly on the guest pasteboard, then verifies `has_image=true` and `has_text=false` with `clipboard.get`. Safari Copy may produce a Web Archive rather than a UIPasteboard image. Therefore direct seeding is valid clipboard regression coverage, but it is **not** evidence that Safari Copy produced an image, nor is it Photos-picker coverage. Empty-composer and existing-text-plus-image runs are separate results so one cannot mask the other.

## Limitations and evidence boundaries

- This is runtime UI automation against one provisioned guest, not a deterministic simulator/XCTest replacement. AX availability, Web content, localization, server fixtures, timing, log retention, and vphone OS behavior can affect replay.
- Photos and Ottie deliberately report environmental non-coverage until controlled fixtures/sessions exist. Do not convert these to passes from screenshots alone.
- Task/Activity checks use existing QA content. Cases 02, 11, and 12 create real QA messages; Saved state may already exist and is handled idempotently. Realtime fixture API responses are used only for IDs/setup and are not accepted as UI-delivery proof.
- The harness does not submit feedback, send an edited image, create tasks, disable radios, or validate TestFlight. It cancels the image editor after proving entry.
- Source review, CI tests, and runtime replay are separate evidence layers. The device-free unit command below validates harness logic only. A green unit run says nothing about a Raft build on vphone; a runtime report says nothing about build provenance unless the reviewer separately records and verifies that provenance.

Run the device-free tests:

```bash
node --test scripts/qa/ios-vphone-smoke/vphone-smoke.test.mjs
git diff --check
```
