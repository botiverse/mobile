# Release pre-login for ordinary Testbed cases (contract, proposal)

Status: **proposal under review (Argus review of PR #4946, round 1: not approved, no staging run
authorized).** The code is gated off at several layers. Nothing in this repo can start a real run:
- no case is `compatible` in the Mobile allow-list;
- Testbed does not yet return a claim binding;
- no pre-login credential is wired.

## Why

Testbed acceptance cases on a release build stop at "login phase UNKNOWN". The CI login phase
(`scripts/qa/login_phase.mjs`) types into a password form, and a release build has no password
form: it signs in through the browser. The release auto-login (#218,
`scripts/qa/release_auto_login.mjs`, [release-auto-login.md](release-auto-login.md)) signs a
release build in through that browser login. This contract makes it a pre-login step for ordinary
Testbed cases, with receipts.

## What runs, and on which bytes

- **The readiness receiver is compiled out of the store build.** Its code sits in
  `compose/androidApp/src/qaReadiness/kotlin`. Only the `debug` build type (so PR CI compiles and
  unit-tests it) and the QA-instrumented build type **`qaRelease`** compile that directory. Only
  `src/qaRelease/AndroidManifest.xml` declares the receiver.
- **`release` and `play` contain neither the class nor the manifest entry.** Gradle tasks
  `verifyReleaseHasNoQaReadinessReceiver` and `verifyPlayHasNoQaReadinessReceiver` check their
  **merged** manifests. Those tasks run before every `assemble`/`bundle` of those variants, so the
  Android Release workflow runs them too. `verifyQaReleaseHasQaReadinessReceiver` checks that the
  candidate declares the receiver disabled and DUMP-protected. PR CI runs all three
  (`test.yml`, "Compile, test and package Android"). A unit test
  (`QaReleaseReadinessReceiptTest`) checks the source-set layout.
- **`qaRelease` is a QA-instrumented candidate, not the store bytes.** It is `release` plus the
  receiver: same package and callback scheme, never published. A pass on it is engineering
  evidence for the pre-login, not store-package acceptance.
- **Shipping the receiver in a real store build is out of scope.** It needs a separate product and
  security approval of the exact packaged surface.

## Scope

- **Testbed decides; Mobile can only narrow.** A case attempt uses the pre-login only when
  Testbed freezes it in a spec and the Stamp claim binds that spec (see Binding). The Mobile
  allow-list `scripts/qa/release_prelogin_allowlist.json` pins exact `(case id, revision,
  case sha256, platform)` entries and can only refuse. An entry must say `status: "compatible"`;
  that change needs Mobile + Testbed review.
- **Never switched** (refused whatever the spec says):
  - real login-UI cases;
  - logout;
  - upgrade;
  - cross-server (multi-server, Android + iOS);
  - bootstrap canary;
  - server create.

  A case is in one of these classes when:
  - the allow-list `never` map names it; or
  - its YAML `tags:` (flow or block list) contain `login`, `logout`, `sign-out`, `upgrade`,
    `update`, `app-update`, `multi-server`, `cross-server`, `cross-platform`, `bootstrap`,
    `canary`, `provisioning`, `server-create` or `manual-only`; or
  - its `auth_fixture` is `none` or missing.

  A YAML form the strict reader does not support (anchors, aliases, YAML tags, multi-line
  scalars, duplicate keys) is refused.
- **Release builds only.** The APK must be **known** non-debuggable: `aapt` must succeed, and if it
  fails the case is refused. Alpha/debuggable builds and bootstrap cases are refused, so Alpha
  fixtures keep their meaning. Existing `alpha-qa` cases on a release build are legitimately
  refused by Testbed admission today, and that stays.
- With the workflow input `release_prelogin` empty (the default), nothing changes.

### `channel-switch-navigation` r3

It is in the allow-list pinned to revision 3, sha256
`0c62efd84eebbbc1ceba998021d9b7a35d5b8f2032a31a419ba080c11748b95c`, with `status: "candidate"`.
It is a later candidate only, not approved.

- It still declares `auth_fixture: alpha-qa`. It must not be retagged to get past admission.
- Opening channels can change read state, so it is **not** zero-write.
- It needs two distinguishable channels on the frozen server.

## Binding

### The Testbed-frozen spec (workflow input `release_prelogin`, contract `testbed-release-prelogin-spec/v1`)

```json
{ "contract": "testbed-release-prelogin-spec/v1",
  "run_case_id": "<Stamp run case>",
  "attempt": { "attempt_id": "pla_<32 hex>", "nonce": "<64 hex>" },
  "case": { "id": "...", "revision": 3, "platform": "android", "case_sha256": "<hex>",
            "goal_sha256": "<sha256 of the goal the agent gets>", "auth_mode": "release-prelogin" },
  "target": { "env": "staging", "account_user_id": "...", "server_id": "...", "server_slug": "...",
              "server_name": "..." },
  "artifact": { "sha256": "<hex>", "package_id": "build.raft.app", "version_name": "...", "version_code": 0 } }
```

`plan` accepts it only when **all** of these hold:

- the Stamp claim of the same run case (`$RUNNER_TEMP/stamp-claim-response-<id>.json`) returns
  `release_prelogin: { spec_sha256, attempt_id }`, where `spec_sha256 = sha256(canonical JSON of
  the spec, keys sorted)` and the attempt id is the same. Without it the spec is caller-asserted
  and refused (`spec_not_bound_by_testbed_claim`). **Testbed does not return this yet**, so today
  every pre-login request is refused here (Testbed-side work);
- `run_case_id` is this run case;
- `goal_sha256` is the sha256 of `inputs.goal`, the goal the agent actually gets;
- `artifact.sha256` equals the APK's sha256 and the frozen `artifact_sha256` input, and the
  package, version name and version code equal the APK's `aapt` badging;
- the case file with that id exists exactly once, its bytes hash to `case_sha256`, and the
  allow-list entry pins the same revision + sha256 and says `compatible`.

### The attempt (Argus decision 7)

- Testbed mints and stores the attempt id and the unpredictable nonce **before dispatch**. The
  workflow mints nothing: the earlier random path is removed. `attempt.json` binds them to this
  workflow run and run attempt (`workflow.run_id`, `workflow.run_attempt`). A rerun is a new
  workflow attempt under the same Testbed attempt, and Testbed decides whether to allow it.
- The runner adds a fresh random **request id** for each readiness request. This is a subordinate
  challenge, never the attempt identity. The nonce is correlation, not authentication.

## Flow

| Step | Who | What |
|---|---|---|
| 1. plan | `release_prelogin.mjs plan` (before the emulator) | The binding checks above. Refused: `refusal.json`, exit 3, the case does not run with a pre-login, and nothing falls back. |
| 2. intent | `login` | `login-started.json` (attempt id, workflow run, time) is written **before** anything touches the device, so a killed run can still be resolved. Requires `QA_PRELOGIN_EMAIL` / `QA_PRELOGIN_PASSWORD`. Without them it stops before any device action (`prelogin_credentials_not_configured`). |
| 3. capture off | `login` | Stops the screen recorder and confirms it is gone. Writes the device marker `/data/local/tmp/qa-prelogin-window`. Starts a recorder watch: an async `pidof screenrecord` every second for the whole window. |
| 4. enable receipt writer | `login` | `pm enable <pkg>/…QaReleaseReadinessReceiver`, then read back as `enabled` from `dumpsys package`. Any other reading, including a failed read, stops here. Changing a component's state stops the app, which is why this is done before sign-in. |
| 5. sign in | `release_auto_login.mjs --no-capture --defer-app-session --strict-cleanup --account-ref-nonce <nonce>` | The #218 flow. `--no-capture` writes **nothing** from the login to disk: UI trees come through `uiautomator dump /dev/tty` into memory, the app's login lines are read with `logcat -d` into memory (no streamed file), and there are no screenshots. Only `summary.json` is written, at the end, into a private dir under `$RUNNER_TEMP` that is never uploaded. The summary carries `account_user_ref`, not the raw account id. |
| 6. pick the server | `login` | On "Choose server", taps the frozen `server_name` (exactly one match), with the UI tree in memory. |
| 7. readiness | the **app** (qaRelease only) | `am broadcast -n <receiver> --es qaAttemptId … --es qaNonce … --es qaRequestId <fresh>`. The app logs one line (tag `RaftQaReadiness`). Up to 6 requests, each with a new request id. |
| 8. end of window | `login` | Stops the watch, scans shared storage for files newer than the marker (`/sdcard`, app-private `Android/` excluded), and reads the recorder once more. |
| 9. login record | `login` | Only when capture, callback and readiness are all good: `login-record.json` (`mode: "release-prelogin"`, `login_end` = the receipt's device stamp, `attempt_id`). `secure_evidence.mjs` keeps only log lines after `login_end`. Otherwise `unknown`: the agent does not run and no evidence is uploaded. |
| 10. case | `acceptance_agent.mjs` | Unchanged. The recorder starts only after a complete record. |
| 11. cleanup | `cleanup` (always reached, see below) | `release_auto_login.mjs --phase sign-out --revoke-app-session --strict-cleanup --no-capture` (the app's own Sign out, #218 bound logout evidence), then `pm disable` read back as `disabled`. |
| 12. outcome | `verdict` (emulator), then `finalize` (always, after the emulator) | `outcome.json` / `outcome.rc`. `finalize` also seals the upload (see below). |
| 13. report | "Report verdict" → `report-gate` | The rc comes only from the bound outcome (see Outcomes). |

### Cleanup is always reached

The emulator runner runs each script line on its own and stops at the first line that fails. On
the pre-login path, every line between the login and the cleanup line ends in
`|| [ -n "${QA_RELEASE_PRELOGIN:-}" ]`, so it cannot stop the script. The Alpha/password path keeps
its exact old behaviour. The login and cleanup lines are bounded with `timeout`.

If the emulator step itself ends before the cleanup line (cancelled, killed, runner lost), the
always() step "Finalize release pre-login receipts" records `cleanup_not_reached`: every cleanup
item is `unknown` and the attempt id is kept. It does **not** claim device cleanup; a stopped
emulator is not a receipt. The session that may be left (QA account, app session) is reconciled by
hand, as in #218. Nothing is retried.

## The app's readiness receipt (contract `raft-qa-release-readiness/v2`)

| Field | Source |
|---|---|
| `attempt_id`, `nonce`, `request_id` | the request extras (format-checked; a malformed request logs `RAFT_QA_READINESS_REJECTED code=bad_request`) |
| `package`, `version_name`, `version_code` | the app's own build |
| `pid`, `process_start_ticks` | `Process.myPid()`, `/proc/self/stat` field 22 |
| `signed_in`, `user_ref` | the last committed session. `user_ref = sha256(UTF-8 "raft-qa-readiness-user/v1" NUL nonce NUL user id)`, lowercase hex, with a fixed test vector shared by the Kotlin and node tests. This is **minimisation**, not anonymisation (the id may be guessable) and not a signature (the nonce is not secret). The runner computes the expected value from the frozen account id. |
| `selected_server_id`, `selected_server_slug` | the session's **stored** selection, never the first-server fallback |

The receipt never carries a token, cookie, email, name or the raw user id. The server id and slug
are in clear text; whether they are sensitive is an open question.

### Binding rules (all must hold, else `unknown`)

1. The installed build was read completely (package, version name **and** code) and equals the
   frozen artifact (`installed_build_unknown` / `installed_build_not_frozen_artifact`).
2. The app process identity was read before and after the request and is the same
   (`app_process_unknown` / `app_process_changed`).
3. A line exists for this attempt id + nonce (`receipt_missing` /
   `stale_receipt_other_attempt_or_nonce`). Exactly one line answers **this request id**
   (`no_answer_to_this_request` / `ambiguous_answers`), so a line left by an earlier request or a
   no-op broadcast never counts.
4. The line's writer pid (logcat) and the receipt's `pid:process_start_ticks` equal the process
   (`receipt_from_other_process`).
5. `signed_in` is true and `user_ref` equals the frozen account's (`app_not_signed_in` /
   `account_mismatch`).
6. The server id **and** slug equal the frozen server (`no_server_selected` / `server_mismatch`).
7. The receipt's package and version equal the installed build (`build_mismatch`).
8. No field has a sensitive-looking name (`receipt_carries_sensitive_field`).

The receipt is a bounded signal, not an anti-forgery attestation and not proof of the visual
route. The installed bytes are bound separately (step 1 of plan: artifact sha256 + badging; the
existing "Verify frozen Android artifact identity" step).

## Receipts per attempt and the upload boundary

`qa-prelogin/` may only ever hold these files. `finalize` deletes everything else, including the
child runs' summaries, which live in `$RUNNER_TEMP` anyway. It then checks each file:
- size;
- JSON shape;
- no sensitive key name;
- no value that looks like a URL, callback, bearer/JWT, `code=`, token, cookie, email or a long
  opaque string (hex ids and hashes are allowed);
- none of the QA secret values the process can see.

If **any** file fails, all of them are withheld and only an `incomplete` outcome is left. The
receipts upload only when `finalize` succeeds. In addition, every receipt write inside the emulator
step is checked against the secret values that step has. These checks cannot recognise every
unregistered secret shape; the allow-list of files and fields is the main guard.

| File | Status values |
|---|---|
| `eligibility.json` / `refusal.json` | `eligible` true/false + reason (+ class) |
| `attempt.json` | Testbed attempt id + nonce, run case, spec sha256, case pins, target (`account_user_ref`, server id/slug/name), artifact, workflow run/attempt |
| `login-started.json` | attempt id, workflow run, time |
| `receipt-capture.json` | `off` / `unknown` + reason (for example `recorder_ran_during_login`, `recorder_watch_missing`, `device_file_scan_missing`, `files_written_during_login` + file names) |
| `receipt-callback.json` | `ok` / `unknown` + reason |
| `receipt-ready.json` | `ok` / `unknown` + reason + facts |
| `receipt-cleanup.json` | three records, below |
| `outcome.json`, `outcome.rc` | below |

### Cleanup: three separate records (Argus decision 4)

| Record | Values | Meaning |
|---|---|---|
| `local_cleanup` | `complete` / `unknown` / `not_started`, per item | What the attempt owns on the device, each item **positively read back**: `capture_file`, `browser`, `capture_browser` (sign-in run), `app_signed_out_on_device` (sign-out run reached the sign-in page; `pm clear` fallback counts), `receipt_writer` (dumpsys read back `disabled`; a failed or empty read is `unknown`). |
| `web_session` | `signed_out_verified` / `not_opened` / `unknown` | The web session the sign-in opened: verified only when its refresh token was refused after logout. |
| `app_server_revocation` | `app_reported_2xx` / `no_app_session` / `unknown` | `app_reported_2xx` means **only** that the app reported a 2xx for its own POST /auth/logout, bound to its process (#218). It never means "revoked". No new server API is used, and no global logout of other sessions. |

## Outcomes (Argus decision 5)

| Condition (in order) | Outcome | rc |
|---|---|---|
| not eligible | `not_run` | 3 |
| capture, callback or readiness not good | `incomplete` (never a product fail or pass) | 3 |
| agent rc 1 **and** verdict.json `fail` | `fail`: a real product fail after verified preparation; the cleanup records are notes beside it | 1 |
| agent rc / verdict anything but (0, `pass`) | `incomplete` | 3 |
| pass, but `local_cleanup` not `complete` | `incomplete` (not a clean pass) | 3 |
| pass, but `web_session` not `signed_out_verified` / `not_opened` | `incomplete` | 3 |
| pass, everything above good | `pass`; `app_server_revocation` (`app_reported_2xx` or `unknown`) is a note, never a condition | 0 |

**Report verdict** takes its rc from `report-gate` alone. The gate recomputes the outcome from
the sealed receipts plus the agent rc and verdict.json **as they are now**. It returns 0 or 1 only
when that equals `outcome.json`, every receipt carries the attempt id, and nothing is missing.
Otherwise it returns 3, which sets the reporter's prerequisite to 1, so Stamp gets `harness`,
never a product verdict. Later gates (required markers, evidence upload, bootstrap) can only
escalate.

## Credentials (Argus decision 8): TODO, not wired

The login takes credentials **only** from `QA_PRELOGIN_EMAIL` / `QA_PRELOGIN_PASSWORD`, and no
workflow sets them. Staging must use a separately reviewed, staging-only credential grant whose
subject is the frozen staging account, scoped to the one step/child that needs it. Wiring
`QA_STAGING_TEST_*` (or any secret) needs its own source/security review covering:
- secret scope (`scripts/ci/check_workflow_secret_env.py` pins);
- preflight;
- the reporter;
- the child env;
- failure cleanup.

`QA_TEST_*` is never used for the pre-login, and the sign-in child's env drops it. Two-step
verification policy stays; there is no shared-account blanket revocation.

## Open items for the next review

1. **Testbed side.** The claim response must return `release_prelogin: { spec_sha256,
   attempt_id }` for admitted attempts, together with the spec contract above. That includes
   minting and storing the attempt id + nonce before dispatch, and the run/rerun policy. Until it
   does, every request is refused at plan.
2. **qaRelease candidate build.** Which workflow builds and signs the `qaRelease` APK, and how its
   identity (sha256, signer) is frozen for Testbed. Not built or triggered in this PR.
3. **Server id/slug** in the receipt and its audience/retention: are they sensitive?
4. **Allow-list promotion.** Who flips `channel-switch-navigation` (or another case) to
   `compatible`, after Testbed admits a release-prelogin mode for it.
5. **Staging credential** review (decision 8).

## Proposed single staging validation: NOT AUTHORIZED

The eventual shape:
- one owner-approved isolated emulator;
- one identified `qaRelease` candidate;
- one reviewed case with two suitable channels;
- the frozen staging account + server;
- short-lived staging-only credentials;
- one claim-bound attempt and one dispatch, with no retry-until-pass.

It needs, first:
- the open items above;
- an independent review of this implementation;
- an explicit execution grant.

## iOS (plan, not built, not reviewed)

The iOS app signs in through `ASWebAuthenticationSession`, which a script cannot capture. A
readiness receipt would follow the same rules:
- a QA-only build configuration that compiles a URL handler in (store builds compile it out);
- the same flat fields through `os_log`;
- process identity from `getpid()` + `sysctl KERN_PROC` start time;
- read with `simctl log show`.

There is no physical iOS device. This PR does not change `commonMain`.
