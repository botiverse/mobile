# Release pre-login for ordinary Testbed cases (contract, proposal)

Status: **proposal, not yet run against a real server.** Code is in place and gated off; the final
review with Argus (Testbed owner) decides the open questions below before a first staging run.

## Why

Testbed acceptance cases on a **release** (store) Android build stop at "login phase UNKNOWN": the
CI login phase (`scripts/qa/login_phase.mjs`) types into a password form, and a release build has
no password form (it signs in through the browser). Debuggable Alpha builds use the QA bootstrap,
which release builds do not have.

The release auto-login (#218, `scripts/qa/release_auto_login.mjs`,
[release-auto-login.md](release-auto-login.md)) already signs a release build in through the
product's own browser login. This contract turns it into a **pre-login step** for ordinary Testbed
cases on release builds, with receipts that say what happened.

## Scope

- **Only ordinary cases, each opted in.** A case is pre-logged-in only when
  `scripts/qa/release_prelogin_allowlist.json` lists it with `intent: "ordinary"`. Adding a case is
  a reviewed change to that file.
- **Never switched**, even if listed: real login-UI cases, logout, upgrade, cross-server
  (Android + iOS / multi-server), bootstrap canary, server create. A case is in one of these
  classes when the allow-list's `never` map names it, or its YAML `tags:` include `login`,
  `logout`, `sign-out`, `upgrade`, `update`, `app-update`, `multi-server`, `cross-server`,
  `cross-platform`, `bootstrap`, `canary`, `provisioning`, `server-create` or `manual-only`, or its
  `auth_fixture` is `none`.
- **Release builds only.** A debuggable build (Alpha) is refused, so Alpha fixtures (`alpha-qa`
  bootstrap, password login) keep their meaning. A bootstrap case is refused.
- With the workflow input `release_prelogin` empty (the default), nothing changes.

## Words

- **Attempt**: one Testbed try of one case. It gets a fresh **attempt id** (`pla_` + 32 hex) and a
  fresh **nonce** (64 hex) when it is planned.
- **Workflow run / rerun**: a GitHub Actions run (`run_id`, `run_attempt`). Recorded next to the
  attempt, never used as the attempt's identity. A rerun plans a new attempt (new id, new nonce).
- **Frozen target**: the QA account and server Testbed fixes for the attempt:
  `env` (`staging` | `production`), `account_user_id`, `server_id`, `server_slug`, `server_name`.
  Ids only. A target that names a token, password, cookie, email or secret field is refused.
- **App process identity**: `<pid>:<start time>`, the start time being field 22 of
  `/proc/<pid>/stat` (clock ticks since boot). The same value the #218 logout evidence uses.

## Flow

| Step | Who | What |
|---|---|---|
| 1. plan | `release_prelogin.mjs plan` (outside the emulator) | Checks eligibility (allow-list, never classes, release build, not bootstrap). Refused: `refusal.json`, exit 3, the case does not run. Allowed: `attempt.json` (attempt id, nonce, case id, run case id, workflow run + attempt, frozen target). |
| 2. capture off | `release_prelogin.mjs login` | Stops the screen recorder and confirms it is gone **before** anything else. If it will not stop, nothing else happens. |
| 3. enable receipt writer | same | `pm enable build.raft.app/build.raft.app.core.data.auth.QaReleaseReadinessReceiver`, read back as enabled. This happens before sign-in: changing a component's state stops the app, and the sign-in clears the app anyway. |
| 4. sign in | `release_auto_login.mjs --no-capture --defer-app-session --strict-cleanup` | The #218 flow: the app's own PKCE request, the capture browser, the web login + approve over HTTP, the callback sent to this app only, and the app's own exchange line. `--no-capture` writes no screenshot, no UI dump and no login log file. `--defer-app-session` leaves the app signed in for the case. The web session the script opened is still signed out and proven (refresh refused). |
| 5. pick the server | `release_prelogin.mjs login` | On "Choose server", taps the frozen `server_name` (exactly one match). The UI tree is read in memory and never saved. |
| 6. readiness receipt | the **app** | `am broadcast -n <receiver> --es qaAttemptId <id> --es qaNonce <nonce>`. The app writes one logcat line, tag `RaftQaReadiness` (fields below). The script reads it, with the app process identity taken before and after the request. Tried up to 6 times, always with the same attempt id and nonce, while the app is still applying the server choice. |
| 7. login record | `release_prelogin.mjs login` | Only when capture, callback and readiness are all good: `qa-evidence/login-record.json` with `mode: "release-prelogin"`, `status: "complete"`, `attempt_id`, and `login_end` set to the receipt's device timestamp. `secure_evidence.mjs` keeps only log lines after `login_end`. Otherwise the record says `unknown`: the agent does not run and no evidence is uploaded. |
| 8. case | `acceptance_agent.mjs` | Unchanged. The recorder starts only after a complete record. |
| 9. cleanup | `release_prelogin.mjs cleanup` (always) | `release_auto_login.mjs --phase sign-out --revoke-app-session --strict-cleanup --no-capture`: the app's own Sign out, with the #218 logout evidence bound to the process. Then `pm disable` on the receiver, read back as disabled. |
| 10. outcome | `release_prelogin.mjs verdict` | `outcome.json` and `outcome.rc`. "Report verdict" turns an agent pass into 3 (unknown, not a product FAIL) unless the outcome is `pass`. |

Credentials: `QA_TEST_EMAIL` / `QA_TEST_PASSWORD` from the "Run acceptance agent" step's own env
(legacy, non-bootstrap path only). They are handed only to the sign-in child, as
`QA_LOGIN_EMAIL` / `QA_LOGIN_PASSWORD`. The sign-out child gets neither. They are never printed. If
any receipt contains them, all receipts are deleted and the outcome is unknown.

## The app's readiness receipt

Written by `QaReleaseReadinessReceiver` (`compose/androidApp/.../core/data/auth/`) as one line:
`RAFT_QA_READINESS {json}`, tag `RaftQaReadiness`, level I.

| Field | Source | Notes |
|---|---|---|
| `contract` | constant | `raft-qa-release-readiness/v1` |
| `attempt_id`, `nonce` | the request extras | Format-checked (`pla_` + hex, hex). A malformed request is rejected and logged as `RAFT_QA_READINESS_REJECTED code=bad_request`. |
| `package`, `version_name`, `version_code` | the app's own build | |
| `pid`, `process_start_ticks` | `Process.myPid()`, `/proc/self/stat` field 22 | |
| `signed_in`, `user_id` | the last committed session (`readCommittedSessionSnapshot`, no lock on the main thread) | `user.id` only |
| `selected_server_id`, `selected_server_slug` | the session's **stored** selection | Not the first-server fallback, so a session still on "Choose server" reports `null`. |

It never carries a token, cookie, email, display name or anything else from the session.

### Binding rules (all must hold, else the receipt is `unknown`)

1. The receipt's `attempt_id` and `nonce` equal this attempt's. A receipt from an earlier attempt,
   or with an earlier nonce, is `stale_receipt_other_attempt_or_nonce`. No receipt at all is
   `receipt_missing`.
2. The app process identity is known and the same before and after the request
   (`app_process_unknown` / `app_process_changed`). The line was written by that pid (logcat's
   own pid column), and the receipt's `pid:process_start_ticks` equals it
   (`receipt_from_other_process`). This catches a restart and a reused pid.
3. `signed_in` is true and `user_id` equals the frozen `account_user_id` (`app_not_signed_in` /
   `account_mismatch`).
4. `selected_server_id` **and** `selected_server_slug` equal the frozen server
   (`no_server_selected` / `server_mismatch`).
5. `package`, `version_name` and `version_code` equal what is installed (`dumpsys package`)
   (`package_mismatch` / `build_mismatch`).
6. The receipt has no field whose name looks sensitive (`receipt_carries_sensitive_field`).

Home or server-picker screenshots are **supporting evidence only**. They are never taken during the
login window, and they do not replace the receipt.

**What the receipt is not:** proof against forgery. It shows that the app process running now says it
is signed in as X on server Y for this attempt. The installed package's identity (download, hash,
version, signer) is checked separately, by the existing "Verify frozen Android artifact identity"
step (`verify_pinned_android_identity.mjs`) and by the APK install.

## Receipts per attempt (`qa-prelogin/`, artifact `qa-release-prelogin-receipts`)

These files are all non-sensitive. They are uploaded even when the evidence gate withholds
`qa-evidence`.

| File | Status values | Meaning |
|---|---|---|
| `eligibility.json` / `refusal.json` | `eligible` true/false + reason (+ class) | Why the case may or may not be pre-logged-in. |
| `attempt.json` | – | Attempt id, nonce, case, run case id, workflow run/attempt, frozen target. |
| `receipt-capture.json` | `off` / `unknown` | The recorder was stopped before login and was still gone after it. The sign-in run used `--no-capture` and wrote 0 screenshots. The harness captured nothing. |
| `receipt-callback.json` | `ok` / `unknown` | The sign-in run was in pre-login mode and reported `signed_in`, the callback was delivered, the app's own exchange line said `exchanged`, and the web login's user id is the frozen account. |
| `receipt-ready.json` | `ok` / `unknown` + reason + facts | The app's readiness receipt checked against the binding rules. |
| `receipt-cleanup.json` | `complete` / `unknown`, per item | See below. |
| `outcome.json`, `outcome.rc` | `pass` / `fail` / `unknown` / `not_run` | See the outcomes table. |
| `sign-in-run/summary.json`, `sign-out-run/summary.json` | – | The #218 summaries (masked; with `--no-capture` no screenshots, dumps or login log). |

### Cleanup items

| Item | Best value | Otherwise |
|---|---|---|
| `device` (capture file, browser role, capture browser) | `ok` | `unknown` |
| `web_session` (the session the script opened to approve) | `ok`: its refresh token was refused after logout | `unknown` |
| `server_session` (the app's own session) | `app_reported_2xx`: the app's own POST /auth/logout answered 2xx, bound to its process after the one logout tap (#218 `app_reported_server_logout_2xx`) | `unknown` |
| `receipt_writer` (the receiver) | `disabled`, read back | `unknown` |

`server_session` is **never `ok`**. The server has no per-session read-back, so even the best value
is the app's own report, not proof that the session was revoked. A cleanup with any `unknown` item is
`unknown` overall.

## Outcomes

| Eligible | Capture | Callback | Readiness | Agent rc | Cleanup | Outcome (rc) |
|---|---|---|---|---|---|---|
| no | – | – | – | – | – | `not_run` (3): case not run with a pre-login |
| yes | not `off` | any | any | any | any | `unknown` (3) |
| yes | `off` | `unknown` | any | any | any | `unknown` (3) |
| yes | `off` | `ok` | `unknown` | any | any | `unknown` (3) |
| yes | `off` | `ok` | `ok` | 1 (product fail) | any | `fail` (1); a cleanup problem is named in the reason |
| yes | `off` | `ok` | `ok` | other than 0/1 | any | `unknown` (3) |
| yes | `off` | `ok` | `ok` | 0 | `unknown` | `unknown` (3): **not** a product pass |
| yes | `off` | `ok` | `ok` | 0 | `complete` | `pass` (0) |

Unknown is never a product pass.

## Who can write and read the receipt (proposal; open)

What is implemented now (gated off by default in **every** build, release included):

- **Write**: the receiver is declared `android:enabled="false"`, so no broadcast reaches it until
  adb's shell enables the component (`pm enable` needs `CHANGE_COMPONENT_ENABLED_STATE`, which
  ordinary apps do not have). It is protected by `android.permission.DUMP`
  (signature|privileged|development: adb's shell holds it, ordinary apps cannot get it). So while
  it is enabled, another app still cannot trigger it. It has no intent filter, so only an explicit
  component broadcast reaches it. The attempt's cleanup disables it again and reads that back.
- **Read**: logcat only. Other apps cannot read another app's logcat lines (they would need
  `READ_LOGS`, a privileged/development permission). adb can.
- **Impact on release builds**: the receiver class and the manifest entry ship in the store build.
  In normal use it is disabled and permission-protected, so it never runs, writes nothing and has
  no UI. When it does run, it reads the committed session snapshot without the session lock (the
  same as `QaCaptureBoundaryReceiver`) and logs one line.

Options for the final review:

| Option | Write gate | Read | Trade-off |
|---|---|---|---|
| **A (implemented)** | Disabled component + DUMP permission + explicit component only | logcat | No build-variant split: the store bits are exactly what is tested. The code ships, but it is dead unless adb enables it. |
| B | A, plus a server-side or build-time switch (for example, a `BuildConfig` flag on for QA-signed release candidates only) | logcat | The store build carries less, but the tested build then differs from the store build. |
| C | Launch-intent extra on `MainActivity` (QA intent) | logcat | Simpler to trigger, but `MainActivity` is exported to every app and cannot be gated by component state. **Not recommended.** |
| D | A, but the receipt goes to a file in the app's external files dir | `adb shell cat /sdcard/Android/data/<pkg>/files/…` | More robust than a logcat ring buffer. The file persists, so it needs deleting in cleanup and adds a leak surface. |
| E | A, plus a one-time HMAC of the receipt with a key from the attempt | logcat | Proves the line answers this request, but the key would have to cross adb too. It adds little over the nonce + process binding. |

## Proposed single staging validation (proposed only, not run)

- **Case**: `channel-switch-navigation` (case-03). It only reads one server's channel list and two
  channels: no writes, no login UI, no sign-out, no server choice as its subject. It is the only
  allow-list entry, marked as pending review.
- **Build**: one release APK from **Android Release** (non-debuggable, `build.raft.app`), with its
  frozen identity (sha256, version, versionCode, package) passed as the existing `artifact_*`
  inputs.
- **Account / server class**: the dedicated QA account (no two-step verification), on **staging**
  (`env: "staging"`), on the QA test server `OranixSlockTest` with its id + slug frozen by Testbed.
  No production account, no personal account.
- **Recorded**: everything listed under "Receipts per attempt" (`attempt.json`, capture, callback,
  ready, cleanup, outcome), plus the usual `qa-acceptance-evidence` (only after `login_end`). The
  run is expected to show `capture: off`, `callback: ok`, `ready: ok` with the frozen ids, and
  `cleanup: complete` with `server_session: app_reported_2xx`. Any other value is reported as found;
  it is not retried until it passes.
- **One** dispatch of `qa-acceptance.yml` with `release_prelogin` =
  `{"case_id":"channel-switch-navigation","target":{"env":"staging","account_user_id":"<frozen>","server_id":"<frozen>","server_slug":"<frozen>","server_name":"OranixSlockTest"}}`.
  It needs the go-ahead first.

## Open questions for the final review with Argus

1. **Receipt write permission.** Is option A enough (disabled by default, enabled by adb only for
   the attempt, DUMP-protected, explicit component)? Or do you want B (a build-time switch, so the
   store build carries no receiver) at the cost of testing different bits from the store?
2. **Receipt read path.** Is logcat (adb only) acceptable? Or do you prefer a file (D) that cleanup
   deletes?
3. **Release-build impact.** Is it acceptable for the store build to carry one disabled,
   DUMP-protected receiver that logs user id + server id/slug + version when adb triggers it? Should
   the user id be hashed (for example, sha256 of user id + nonce) instead of sent in clear?
4. **Server session revoke.** Is `app_reported_2xx` (the app's own bound report of a 2xx logout) the
   accepted best value for `cleanup: complete`? Or must the attempt stay `unknown` until the server
   offers a per-session read-back? If the latter, no attempt can pass today.
5. **Product FAIL with cleanup unknown.** This proposal keeps an agent FAIL as `fail` and names the
   cleanup problem beside it. Should it be `unknown` instead?
6. **Allow-list ownership.** Who approves adding a case to `release_prelogin_allowlist.json`?
   Should Testbed send the case's intent itself (per-case field in the case YAML) instead of the
   repo-side list, or both?
7. **Attempt id source.** Today `plan` mints the attempt id and nonce in the workflow. Should
   Testbed mint them (so its own records hold them before dispatch) and pass them in?
8. **Credentials.** The pre-login uses `QA_TEST_EMAIL` / `QA_TEST_PASSWORD` (the account exists on
   staging and production). Should staging runs use `QA_STAGING_TEST_*` as `release-auto-login.yml`
   does? The secret-scope checker pins the acceptance step's env, so that would be a reviewed
   change.

## iOS (plan, not built)

The iOS app signs in through `ASWebAuthenticationSession`, which a script cannot capture. See
[release-auto-login.md](release-auto-login.md#ios-follow-up-not-built). The readiness receipt would
be:

- **Writer**: a handler for a QA-only custom URL (`raft-qa://readiness?attempt=…&nonce=…`), off by
  default. It would be enabled only by a launch argument or environment variable that
  `xcrun simctl launch` can set (store users cannot set those). It writes the same flat fields
  through `os_log` (subsystem `build.raft.app`, category `RaftQaReadiness`), with process identity
  `getpid()` + the process start time (`sysctl KERN_PROC`).
- **Reader**: `xcrun simctl spawn booted log show --predicate 'category == "RaftQaReadiness"'`,
  with the same binding rules.
- The shared session fields come from `commonMain` (`StoredAuthSession`). Only the iOS host glue
  would be new; this PR does not change `commonMain`.
