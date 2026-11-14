# QA session bootstrap (`qa-bootstrap-login/v1`)

The QA system (Stamp / Testbed) can start the app **already signed in** instead of
walking the login UI. The harness gets a one-shot, device-bound login envelope from
Stamp, hands it to the app through a private channel, and the app persists the
session through the shared auth session store and logs an exact `QA_BOOTSTRAP_READY`
line once the signed-in shell is on its route.

Android and the iOS Simulator share one contract: the same envelope, the same
validation (`SharedAuthDataSource.consumeQaLoginBootstrap`), the same diagnostics
lines and the same harness output files. Only the device channel differs.

| | Android | iOS Simulator |
|---|---|---|
| Harness | `scripts/qa/mobile_login_bootstrap.mjs` | `scripts/qa/ios_qa_bootstrap.mjs` |
| App entry | `QaSessionBootstrapActivity` (+ `QaSessionBootstrapInbox`) | `RaftIosQaSessionBootstrapHost.swift` (+ `RaftIosQaSessionBootstrapContract.swift`), Kotlin `RaftIosQaSessionBootstrapBridge` |
| Built into | `alpha` / `connectedTest` (`qa_session_bootstrap_enabled`), debuggable only | the **Debug** configuration only (`RAFT_QA_SESSION_BOOTSTRAP`), Simulator only |
| Envelope delivery | `run-as` into `files/qa-bootstrap/inbox/<request_id>.session` | write into the app data container: `Library/Application Support/qa-bootstrap/inbox/<request_id>.session` |
| Trigger | `am start -n <pkg>/…QaSessionBootstrapActivity --es qaBootstrapRequestId <id> --es qaBootstrapEnvelopeDigest <digest>` | `xcrun simctl launch --terminate-running-process <udid> <bundle> -qaBootstrapRequestId <id> -qaBootstrapEnvelopeDigest <digest>` |
| Log channel | logcat, tag `SlockQaBootstrap` | unified log via NSLog, `[SlockQaBootstrap][info] …`; read with `xcrun simctl spawn <udid> log stream --predicate 'eventMessage CONTAINS "SlockQaBootstrap"'` |

## Flow

1. **Binding.** The harness computes the device binding and writes
   `{"signing_cert_sha256": "...", "boot_generation": "..."}` (mode 0600).
   `stamp_report.sh bootstrap-login <binding.json>` posts it to the case's
   `login_endpoint`; Stamp answers with
   `{request_id, envelope_digest, envelope, expires_at}`.
   Issuance is called **at most once per run case on this runner** (no `curl --retry`, no
   second login, no new attempt): a retry after a lost reply could not recover the refresh
   token of a session that may already be live. The reporter first takes an exclusive
   lock (`mkdir stamp-bootstrap-login-lock-<run_case_id>` in `RUNNER_TEMP`: atomic, so of
   concurrent invocations exactly one proceeds; never released or broken automatically),
   then persists an `in_flight` intent atomically (temp file + rename) in
   `stamp-bootstrap-login-outcome-<run_case_id>.json` **before** calling the endpoint, and
   replaces it with the outcome afterwards (copied to
   `qa-evidence/bootstrap-login-outcome.json`; no secrets):
   - `issued` — 2xx whose body is a well-formed envelope for this run case (request id,
     `sha256:` digest, `envelope.run_case_id`, non-empty refresh token; `prepare` then
     validates it exactly);
   - `rejected` — **only** a 4xx whose body is the server's exact-attempt credential-rejection
     receipt: exactly `{contract:"qa-bootstrap-login-rejection/v1", issued:false,
     reason:"credential_rejected", run_case_id, attempt, ci_job_ref,
     signing_cert_sha256, boot_generation}` with this run case, this CI job and the posted
     binding. Testbed does not emit this receipt yet, so today every 4xx is `unknown`;
   - `unknown` (exit 3) — everything else: generic 4xx (including `409
     BOOTSTRAP_LOGIN_RETRY_REQUIRES_NEW_ATTEMPT`, which may mean an earlier issuance exists),
     5xx, partial/unproven 2xx, timeout, reset, empty reply, and local connect failures
     (a local connect result is not proof that nothing was issued).
   A sender killed at any point leaves the lock and the `in_flight` intent: the next
   invocation refuses to issue, and nothing falls back to "not sent". On anything but
   `issued`/`rejected` the run stops: `bootstrap-failure-terminal` refuses to post the "no
   material issued" terminal, and the attempt is left for operator reconciliation with
   Testbed. Shared QA sessions are never touched.
   **Scope:** this is a local, per-runner guarantee. It does not prove one-shot issuance
   across runner loss (a re-run on a new runner has a fresh `RUNNER_TEMP`); that needs a
   Testbed server-side, attempt-bound issuance fence and reconciliation path (follow-up).
2. **Prepare.** The harness re-validates the response (`validateLoginResponse`: exact
   key set, `version`, `run_case_id`/`ci_job_ref` of this run, `attempt >= 1`,
   `target_route`, `application_id` = the installed app, both binding fields,
   `principal_id == user.id`, non-empty tokens, and
   `envelope_digest == sha256(canonicalJson(envelope))`), writes the canonical bytes
   into the private inbox and launches the app with the request id and digest only
   (never the tokens).
3. **Consume.** The app consumes the inbox file exactly once, checks the digest,
   validates shape and binding, then `consumeQaLoginBootstrap` re-checks the binding,
   clears any previous session, resolves `server_id` against the account's servers and
   persists the session (with `qaBootstrap` metadata) in the shared auth session store.
4. **READY.** When the shell's gate is Ready and Home is Content/Empty, the shared
   `completeQaBootstrapRouteReady` persists the local ready receipt and the runtime
   envelope and logs `QA_BOOTSTRAP_READY …`. The harness accepts only a READY line whose
   fields match the envelope exactly (`parseReadyLine`) and writes `ready.json`
   (`readyPayload`), which `stamp_report.sh bootstrap-ready` posts.
5. **Teardown.** `cleanup` stops the app, clears its data and writes the
   `device_cleanup` record `stamp_report.sh bootstrap-terminal` sends with the refresh
   token, so Stamp can revoke the session. The iOS record has its own fields (below), not
   Android's `force_stopped`/`package_data_cleared`.

Expiry (`expires_at`), one-use issuance, the account/server allowlist and revocation
are enforced by Stamp; the app side guarantees one-shot local consumption and the
device binding.

## Envelope

Canonical JSON (keys sorted, no whitespace), at most 16 KiB:

```json
{"access_token":"…","application_id":"build.raft.app","attempt":1,
 "boot_generation":"sha256:<64 hex>","ci_job_ref":"https://github.com/botiverse/mobile/actions/runs/<id>",
 "principal_id":"<user id>","refresh_token":"…","run_case_id":"<run case id>",
 "server_id":"<server id>","signing_cert_sha256":"sha256:<64 hex>",
 "target_route":"compose_slock_app_shell","user":{"id":"<user id>","email":"…"},
 "version":"qa-bootstrap-login/v1"}
```

Exactly these 13 keys. `envelope_digest` is `sha256:` + lowercase hex of those bytes.
`request_id` matches `[A-Za-z0-9._-]{1,80}`.

### Binding values

| Field | Android | iOS Simulator |
|---|---|---|
| `application_id` | package name (`build.raft.app.alpha`) | `CFBundleIdentifier` of the installed app (`build.raft.app`) |
| `signing_cert_sha256` | `sha256:` of the APK signing certificate for the device SDK | **code identity**: `sha256:` over the installed main executable's bytes (`<app>/<CFBundleExecutable>`, from `xcrun simctl get_app_container <udid> <bundle> app`) followed by `<app>/<CFBundleExecutable>.debug.dylib` when present (Xcode Debug builds keep the app code in that dylib behind a stub executable). Simulator builds carry no distribution certificate, so the field binds the exact binary instead |
| `boot_generation` | `sha256:` of `/proc/sys/kernel/random/boot_id` | `sha256("ios-simulator-boot/v2|<SIMULATOR UDID, uppercase>|<launchd_sim start, epoch seconds>")`: **per Simulator boot**. CoreSimulator starts a fresh `launchd_sim` for the device on every `simctl boot`; the app reads its launchd_sim ancestor's `p_starttime` via `sysctl(KERN_PROC_PID)`, the harness finds the launchd_sim whose argv carries `/Devices/<UDID>/` (`ps -axww -o pid=,args=`) and reads `LC_ALL=C TZ=UTC ps -o lstart= -p <pid>` (the same field, seconds). Resolution is one second on both sides, so two boots of the same UDID whose launchd_sim starts fall within the same UTC second would get the same value: a bounded collision, documented and unit-tested, not "different on every boot" (a shutdown + boot + `bootstatus` cycle takes far longer than a second). The UDID comes from `SIMULATOR_UDID` (or the CoreSimulator path of the app home). Not `kern.boottime`: Simulator processes run on the host kernel, so that is the Mac's boot and does not change when the Simulator reboots (v1 used it and only distinguished host boots). It distinguishes boots of one Simulator (an erase needs a shutdown, so a new boot follows); it does not say whether the data was erased, and it is not a trusted seat or lifecycle attestation |

For iOS, Stamp issues the envelope exactly as for Android, with `application_id` set
to the bundle id of the pinned iOS artifact (`artifact_package_id`) and the two
binding fields copied from the posted binding file.

## iOS Simulator delivery (what Testbed implements)

Prerequisites: a **Debug** build (`qa-acceptance-ios.yml` / `ios-simulator-app.yml`
build Debug), installed on a booted Simulator. `ios_qa_bootstrap.mjs binding` refuses
a build without the bootstrap compiled in (the Android pre-flight refuses
non-debuggable APKs the same way).

```bash
node scripts/qa/ios_qa_bootstrap.mjs binding --udid "$UDID" --bundle-id "$BUNDLE_ID" \
  --output "$RUNNER_TEMP/stamp-bootstrap-device-binding.json"
bash scripts/qa/stamp_report.sh bootstrap-login "$RUNNER_TEMP/stamp-bootstrap-device-binding.json"
node scripts/qa/ios_qa_bootstrap.mjs prepare --udid "$UDID" --bundle-id "$BUNDLE_ID" \
  --response "$RUNNER_TEMP/stamp-bootstrap-login-$STAMP_RUN_CASE_ID.json" \
  --binding "$RUNNER_TEMP/stamp-bootstrap-device-binding.json" \
  --run-case-id "$STAMP_RUN_CASE_ID" --ci-job-ref "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" \
  --ready-output "$RUNNER_TEMP/stamp-bootstrap-ready.json"
bash scripts/qa/stamp_report.sh bootstrap-ready "$RUNNER_TEMP/stamp-bootstrap-ready.json"
# … run the case on the signed-in app …
node scripts/qa/ios_qa_bootstrap.mjs cleanup --udid "$UDID" --simulator-name "$SIM_NAME" --bundle-id "$BUNDLE_ID" \
  --response … --binding … --ready "$RUNNER_TEMP/stamp-bootstrap-ready.json" --outcome completed \
  --output "$RUNNER_TEMP/stamp-bootstrap-cleanup.json" --run-case-id … --ci-job-ref …
bash scripts/qa/stamp_report.sh bootstrap-terminal completed "$RUNNER_TEMP/stamp-bootstrap-cleanup.json"
```

`prepare` does, in order:

1. `xcrun simctl get_app_container <udid> <bundle> data` → `<data>`.
2. `mkdir -p "<data>/Library/Application Support/qa-bootstrap/inbox"` (mode 0700).
3. Write the canonical envelope bytes to `.<request_id>.session.tmp` (mode 0600),
   rename to `<request_id>.session`, read back mode/size/sha256.
4. Start `xcrun simctl spawn <udid> log stream --style compact --predicate 'eventMessage CONTAINS "SlockQaBootstrap"'`.
5. `xcrun simctl launch --terminate-running-process <udid> <bundle> -qaBootstrapRequestId <request_id> -qaBootstrapEnvelopeDigest <envelope_digest>`.
6. Wait (≤ 120 s) for the exact-bound `QA_BOOTSTRAP_READY` line; on timeout report the
   `QA_BOOTSTRAP_STAGE`/`FAILURE` stages and the app's `QA_BOOTSTRAP_DIAG` line.

`cleanup` (and `smoke`) first require a Simulator **owned by this job**.
`qa-acceptance-ios.yml` creates the device fresh for every run as
`raft-qa-run-<run id>-<attempt>` (it never reuses a device found by name) and right after
`simctl create` runs `ios_qa_bootstrap.mjs record-ownership`, which writes the ownership
receipt `$RUNNER_TEMP/raft-qa-simulator-ownership.json` once (exclusive create, mode 0400):
`{contract:"raft-qa-simulator-ownership/v1", udid, name, github_run_id,
github_run_attempt, created_at}`. Immediately before every destructive action (terminate,
uninstall, the smoke's launches and shutdown) the harness re-verifies: the receipt is a
regular file of this uid with no group/other permissions and the exact shape; its run
id/attempt are this job's `GITHUB_RUN_ID`/`GITHUB_RUN_ATTEMPT`; its UDID/name equal
`--udid`/`--simulator-name`, which must be `raft-qa-run-<GITHUB_RUN_ID>-<GITHUB_RUN_ATTEMPT>`;
and a fresh `simctl list devices -j` shows that UDID exactly once under that name, with no
other device of that name. A missing, mismatched or ambiguous ownership is refused before
anything is stopped. The workflow's last step deletes the device only after
`verify-ownership` passes as well. Then
`cleanup` terminates the app, `simctl uninstall`s it (the data container holds the MMKV
session and the inbox; the app stores nothing in the keychain) and classifies each read
as **present / absent / unknown**:

- app process — two independent reads, and `absent` needs both:
  `xcrun simctl spawn <udid> launchctl list` is `present` if a running
  `UIKitApplication:<bundle>[…]` job is listed; it says `absent` only if the command
  succeeded, the listing is structurally whole (ends with a newline, every line is the
  header or a `pid\tstatus\tlabel` row) and shows a running positive-control service
  (`com.apple.SpringBoard` or `com.apple.backboardd`). The control proves the listing
  covered that service, not that it was complete (a cut at a line boundary is invisible),
  so the host `ps -axww -o pid=,args=` must also succeed, end with a newline, list this
  Simulator's launchd_sim (positive control) and no `<App>.app/<CFBundleExecutable>`
  process under this device's bundle containers. Non-zero exit, timeout, truncated or
  control-less output of either read is `unknown`.
- data container — `xcrun simctl get_app_container <udid> <bundle> data`: `present` on
  exit 0 with a path (or when `simctl listapps` still lists the bundle). `absent` only if
  ALL hold: a control read of a never-installed bundle id (same command, same moment)
  fails with the same exit status and the same non-empty error text (bundle id masked);
  that text is not an infrastructure error (shutdown/state, timeout, connection, XPC,
  CoreSimulatorService, invalid device, …: identical errors from a broken service prove
  nothing); a fresh `simctl list devices -j` shows the device `Booted`; and a structurally
  complete `simctl listapps <udid>` lists a system positive-control app
  (`com.apple.Preferences` or `com.apple.mobilesafari`) and not the bundle. Anything else
  (timeouts, spawn errors, generic matching errors without the positive controls) is
  `unknown`.

The record is:

```json
{"platform":"ios-simulator","application_id":"…","simulator_udid":"…","simulator_name":"raft-qa-run-…",
 "boot_generation":"…","request_id":"…","envelope_digest":"…","process_instance_id":"…","process_generation":"…",
 "cleanup_receipt":"clean|failed|unknown",
 "app_process":{"method":"…","state":"absent",
   "launchctl":{"state":"absent","reason":"app_job_not_listed","positive_control":"com.apple.SpringBoard"},
   "host_process":{"state":"absent","reason":"app_process_not_listed","positive_control":"launchd_sim"}},
 "app_uninstall":{"method":"xcrun simctl uninstall","exit_status":0,"timed_out":false},
 "app_data_container":{"method":"…","state":"absent","reason":"not_installed_confirmed","positive_control":"com.apple.Preferences","error_signature":"…"}}
```

`cleanup_receipt` is `clean` only for process `absent`, uninstall exit 0 and container
`absent`; `failed` if either read is `present`; otherwise `unknown`, never clean. `cleanup`
exits 0 only for `clean`. It is a statement about this app on this Simulator, not proof
that other sessions of the account were revoked (that is the separate logout receipt).

Why a file plus launch arguments: launch arguments and `SIMCTL_CHILD_*` environment
values are visible to every process on the host (`ps`) and land in logs, so they carry
only the request id and the digest. The tokens travel in a 0600 file inside the app's
own container, which the app unlinks before it reads it.

After READY the case driver may keep using the running app. If it relaunches the app
(for example `agent-device open --relaunch`), the shell finds the stored `qaBootstrap`
session without its in-process launch authorization and re-authorizes it through the
shared recovery path (`QA_BOOTSTRAP_FAILURE stage=shell_gate code=recovery_required`
followed by `stage=shell_gate_recovered`), as on Android. The same pair can also appear
on the first iOS launch when the shell opens its own auth store instance after the
bootstrap wrote the session; it is informational, the harness binds to READY only.

## App-side validation (iOS)

`RaftIosQaSessionBootstrapContract.consumeInbox` is a port of
`QaSessionBootstrapInbox.consumeDetailed`: `lstat` (no symlinks), regular file,
1…16384 bytes, mode exactly 0600, owned by the app's uid; `open(O_NOFOLLOW)`; `fstat`
must match the `lstat`'d device/inode; **unlink before reading** (one-shot even when a
later check fails); read exactly `st_size` bytes. Then `validateEnvelope` checks the
digest, the exact key set and shape, and the binding against what the app computes
for itself; only then does the Kotlin bridge call `consumeQaLoginBootstrap`, which
checks the binding again. The shell is held back (blank canvas) until this finishes;
on any failure the app simply starts signed out.

## Diagnostics

All lines carry stage/code tokens, digests and paths only — never tokens or envelope
contents.

| Line | Meaning |
|---|---|
| `QA_BOOTSTRAP_STAGE stage=bootstrap_started` (iOS) / `activity_created` (Android) | request accepted |
| `QA_BOOTSTRAP_DIAG protocol=raft-ios-qa-session-bootstrap/v1 home=… path=… inboxEntries=… applicationId=… codeIdentity=… bootGeneration=…` (iOS) | reader-side coordinates and the binding the app computed; compare with the binding file |
| `QA_BOOTSTRAP_STAGE stage=inbox_consumed` | file consumed (and already unlinked) |
| `QA_BOOTSTRAP_STAGE stage=envelope_validated` | digest, shape and binding passed |
| `QA_BOOTSTRAP_STAGE stage=session_persisted`, `stage=shell_launch_requested` | session saved; shell starting |
| `QA_BOOTSTRAP_STAGE stage=route_ready_started` … `runtime_envelope_persisted` | READY handshake |
| `QA_BOOTSTRAP_READY envelopeDigest=… runCaseId=… attempt=… ciJobRef=… principalId=… serverId=… sessionGeneration=… route=… applicationId=… bootGeneration=… pid=… processInstanceId=… processGeneration=… deviceReadyElapsedRealtimeNanos=… readyReceiptDigest=… runtimeEnvelopeDigest=… routeReadyProofDigest=…` | the line the harness binds to |
| `QA_BOOTSTRAP_FAILURE stage=launch_arguments code=arguments\|request_id\|envelope_digest` (iOS) | malformed launch arguments |
| `QA_BOOTSTRAP_FAILURE stage=runtime_gate code=not_simulator` (iOS) | Debug build on a real device |
| `QA_BOOTSTRAP_FAILURE stage=inbox_consume code=request_id\|suffix\|directory\|parent\|missing\|stat_denied\|stat_error\|not_regular\|size\|mode\|owner\|changed\|remove\|read\|open` | inbox refusal (`owner` is iOS-only) |
| `QA_BOOTSTRAP_FAILURE stage=envelope_digest code=mismatch\|format` | digest differs from the launch argument |
| `QA_BOOTSTRAP_FAILURE stage=envelope_shape code=json\|keys\|version\|run_case_id\|attempt\|ci_job_ref\|principal\|server_id\|target_route\|access_token\|refresh_token\|utf8` (iOS) | malformed envelope |
| `QA_BOOTSTRAP_FAILURE stage=envelope_binding code=application_id\|code_identity\|boot_generation\|unavailable` (iOS) | envelope bound to another app, binary or Simulator boot |
| `QA_BOOTSTRAP_FAILURE stage=login_consume code=<code>` | shared consumer refused (`unauthenticated`, a `SharedAuthErrorCode` name, `runtime_gate_release_binary`, `runtime_gate_not_simulator`, `exception`) |

## Release exclusion (iOS)

- The Swift host and contract files are wrapped whole in `#if RAFT_QA_SESSION_BOOTSTRAP`;
  the only call sites (`iOSApp.init` starting the gate, `RaftIosRootView` holding the shell) sit
  inside the same `#if`. The condition is set in
  `SWIFT_ACTIVE_COMPILATION_CONDITIONS` of the `iosApp` target's **Debug** configuration
  only; the Release configuration (`ios-release.yml` archive → TestFlight / App Store)
  never defines it.
- `scripts/ci/check_ios_qa_bootstrap_exclusion.py source` (every PR, `test.yml` plan job)
  fails if any other build configuration, xcconfig, script or workflow defines the flag,
  or if any Swift file references the bootstrap outside the `#if`.
- `compose/iosApp/scripts/test_qa_session_bootstrap_contract.sh` (every iOS compile)
  compiles the contract file without the flag and fails if the object carries any of it.
- `ios-release.yml` runs `check_ios_qa_bootstrap_exclusion.py binary <Payload/*.app>`
  on every signed IPA and fails the release if any marker string
  (`raft-ios-qa-session-bootstrap`, `qaBootstrapRequestId`, `qa-bootstrap/inbox`, …)
  is present.
- The Kotlin `RaftIosQaSessionBootstrapBridge` is part of the shared framework in every
  configuration (Kotlin has no per-configuration sources) but has no caller in Release
  and refuses on its own outside a Kotlin debug binary (`Platform.isDebugBinary`) and
  outside the Simulator; the iOS READY emitter is gated the same way.

## Smoke check without an account

`qa-acceptance-ios.yml` with `mode=bootstrap-smoke` runs
`ios_qa_bootstrap.mjs smoke` on the freshly built Debug app: fake-token envelopes,
correctly bound, through the real channel. It passes when

- a delivered envelope is consumed (file gone), validated, and reaches the shared
  consumer (`login_consume` fails on the fake token — proof that the binding the host
  computed equals the app's, and that the Kotlin bridge is live);
- replaying the same request id yields `inbox_consume code=missing`;
- a wrong digest yields `envelope_digest code=mismatch` after consumption;
- a 0644 file yields `inbox_consume code=mode` and is left untouched;
- an envelope bound to another boot yields `envelope_binding code=boot_generation`;
- after `simctl shutdown` + `simctl boot` of the (job-owned) Simulator, the harness's
  `boot_generation` and launchd_sim start time have changed, the value the app logs in
  `QA_BOOTSTRAP_DIAG` equals the harness's before and after, an envelope bound to the
  previous boot is refused (`envelope_binding code=boot_generation`) and one bound to the
  new boot is validated. `smoke-report.json` `reboot` also records `kern.boottime` read
  inside the Simulator and on the host, as evidence of why it is not used;
- the device cleanup ends with `cleanup_receipt: clean`.

Evidence: `qa-evidence/bootstrap-smoke/{smoke-report.json,bootstrap-log.txt,after-delivery.png}`.

## Not wired yet (iOS)

`qa-acceptance-ios.yml` does not yet run the Stamp-claimed auth-bootstrap path
(`bootstrap_required: true`); it still skips the UI credentials for such cases. Two
things must land first: Stamp issuing iOS envelopes (`application_id` = bundle id,
binding fields as above), and an iOS answer to the capture proof that
`stamp_report.sh report-evidence` requires for bootstrap runs (Android's
`QaCaptureBoundaryReceiver`; the iOS app has no equivalent). Once both exist, the
commands above slot into the workflow where Android's `qa-acceptance.yml` calls
`mobile_login_bootstrap.mjs`, and `stamp_report.sh bootstrap-failure-terminal` needs an
iOS branch for its `adb` cleanup fallback. Testbed admission also still rejects iOS
auth-required coordinates on purpose, and its terminal validator would need to accept the
iOS `device_cleanup` fields above.
