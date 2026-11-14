# Release build auto login (Android)

`scripts/qa/release_auto_login.mjs` signs a **release** (non-Alpha, non-debuggable) Android build in
on an emulator with a QA account, through the same browser login a person uses. The app has no QA
hook for it: the debuggable-only `QaSessionBootstrapActivity` (`mobile_login_bootstrap.mjs`) is not
in release builds, and nothing here adds one.

Workflow: **Android Release Auto Login** (`.github/workflows/release-auto-login.yml`), dispatch with
`environment` = `staging` (default) or `production`, and optionally `apk_run_id` + `apk_artifact`
to reuse a release APK instead of building one. `strict_cleanup` and `revoke_app_session` are on by
default (see [Cleanup](#cleanup)). Other emulator workflows can copy its two steps "Build the QA
capture browser" and "Sign in on the emulator".

## What a pass means

`result: "signed_in"` (exit 0) means: on this emulator, a release build of the app started its own
app-login request, the login was approved for the QA account through the same API calls the web
login + confirm page makes, the callback reached this app only, the app exchanged the code with its
own PKCE verifier and showed a signed-in screen — **authorize → callback → session verified** — and,
in strict mode, every cleanup step below succeeded.

It does **not** mean:

- **the browser UI works**: no web page is rendered; the login form, the confirm page, Chrome /
  Custom Tab behaviour and the browser → app hand-off prompt are not exercised;
- **anything about production accounts**: it uses a dedicated QA account (staging by default);
  never point it at a real person's account;
- **it is safe on a shared or personal device**: it takes over the default browser, reinstalls /
  clears the app and (with `--revoke-app-session`) signs the app out. Run it only on a throwaway
  emulator.

## What happens

1. **Fresh app, real button.** The APK is (re)installed, the app opens on "Sign in to Raft". For
   staging the script fills the product's own **Sign in to a self-hosted server** sheet (web
   `https://raft-app-staging.botiverse.dev`, API `https://api-aws-staging.botiverse.dev`) and waits
   for "Sign in through raft-app-staging.botiverse.dev". It ticks the consent box and taps
   **Sign in to Raft**.
2. **The app starts the login itself.** It creates its PKCE verifier, calls
   `POST /api/auth/app-login/start` with `raft://login/callback` and the challenge, and opens the
   returned `<web>/login/app?request=<id>` in the default browser (an explicit browser intent,
   `androidExternalBrowserIntent`, so App Links cannot route it back to Raft).
3. **Capture.** The default browser on the emulator is the **QA capture browser**
   (`scripts/qa/capture-browser`, package `build.qa.capturebrowser`): a ~50-line activity that holds
   the browser role (`cmd role add-role-holder`; if the image refuses, the other browsers are disabled
   for the run and re-enabled in cleanup), writes the URL it is asked to open to its private `files/captured_url` and closes.
   The script reads it with `adb shell run-as build.qa.capturebrowser cat …` (the stub is debuggable;
   the app is not) and deletes it at once. The stub is built in CI by `build.sh` from the SDK's own
   `aapt2`/`javac`/`d8`/`apksigner` with a throwaway key; it is not a Gradle module and never ships.
4. **The web page's requests, over HTTP.** The script does exactly what the web login + confirm page
   send (`packages/web` `authStore.login`, `pages/AppLoginPage.tsx`): `POST /api/auth/login`
   `{email, password}` → bearer access token; `GET /api/auth/app-login/requests/:id`;
   `POST /api/auth/app-login/requests/:id/approve` → `{redirectUrl}`.
5. **Callback to this app only.** `raft://login/callback?requestId=…&code=…` is delivered with
   `am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d <callback> build.raft.app`
   (explicit package), as a browser redirect would. The app checks the request id against its pending
   request and exchanges code + its own verifier (`POST /api/auth/app-login/complete`).
6. **Verify.** Signed in = Home ("Drafts") or "Choose server … Signed in as …" on screen with no
   "Sign in to Raft", **and** the app's own `SlockAppLogin` line
   `app_login_complete result=app_shell reason=exchanged` (read from a logcat dump on each poll;
   `verify-signed-in` → `exchange: "exchanged"`, `exchange_log`). Any other final result
   (`login_error`, `stale`, `ignored`) fails the run at once. If the screen is signed in but the line
   never appears within 30s, strict mode fails the run; without strict mode it passes with
   `exchange: "not_logged"`, `exchange_verified: false` and a warning.
7. **Clean up (always)**, each step with its own status: see [Cleanup](#cleanup).

## Guarantees

| Requirement | How |
|---|---|
| No server check is bypassed | Only the endpoints the web page calls, with the same bodies and a bearer token; there is no CSRF token or captcha on these routes (the web client uses bearer tokens, not cookies). Two-step verification (`secondFactorRequired`, or `second_factor_step_up_required` on approve) and HTTP 429 from the auth rate limiter **stop the run**; nothing is retried in a loop. |
| PKCE and state stay bound to this app session | The script never builds an authorize URL: it only reads the one the app opened. The verifier never leaves the app. The captured URL must be `https://<expected web host>/login/app` with exactly one `request`; the approval's redirect must be `<app scheme>://login/callback` with exactly `requestId` + `code` and the **same** `requestId` (the flow's state), else the run stops before anything reaches the app. |
| Code / callback only to this app, never logged | Explicit-package `am start`, sent on adb's stdin (not the host command line) with its output discarded. Email, password, tokens, request id, code and callback URL are masked in every log line, the summary and the logcat excerpt; no screenshot is taken while the code exists outside the app. |
| Cleanup on exit | `finally`, every step runs and records its own status under `cleanup` in `summary.json`; `--strict-cleanup` fails the run on any cleanup failure. See [Cleanup](#cleanup). |

## Cleanup

Every step runs whatever happened before it (a failing step does not skip the next) and records
one status in `summary.json` → `cleanup`. `cleanup_ok` is true only when every step has an OK status;
`cleanup_failures` lists the others.

| Step (`cleanup.<key>`) | OK statuses | Failure statuses | What is checked |
|---|---|---|---|
| `web_session`: the web session the script opened to approve | `signed_out`, `not_opened` | `failed` | `POST /api/auth/logout` with its refresh token, then `POST /api/auth/refresh` with the same token must answer 401 (`web_session_refresh_check`, e.g. `401 session_revoked`). Logout alone answers 200 even for an unknown token, so it is not proof. |
| `app_session`: the app's own session (`--revoke-app-session`) | `server_logout_verified`, `not_signed_in` | `local_cleared_server_unconfirmed`, `not_requested`, `failed` | The app's own Sign out (Choose server → **Sign Out**; with a server selected: the server tab → server panel → **Settings** (scrolled into view) → **Account** → scroll → **Sign out** → confirm **Sign out**), then the app must be back on "Sign in to Raft" / "Sign in through …". The app sends `POST /api/auth/logout` with its refresh token itself; cleanup then reads logcat (every tag, up to 20 s) for the app's own lines and keeps only non-secret fields in `app_logout_log`: `auth_logout_api_result result=…`, `auth_logout_cleared serverRevocation=…` and the HTTP layer's `method=POST path=…/auth/logout status=… traceId=…`. `server_logout_verified` needs `serverRevocation=success` (or `result=success`, or a 2xx for that request). Without that evidence, or if the UI route fails (`pm clear build.raft.app` wipes the app's tokens), the status is `local_cleared_server_unconfirmed`: the server session may live on until it expires (30 days). `app_sign_out_route` / `app_sign_out_error` say what happened; a failed route also saves `cleanup-app-sign-out-FAILED` (screenshot + UI dump). |
| `capture_file`: the captured login URL | `deleted`, `not_applicable` | `failed` | Removed, then checked absent with `run-as`. |
| `browser`: the default browser | `restored`, `not_changed` | `failed` | Browser role back with the previous holder(s) and not with the stub; every browser disabled for the run is enabled again (`browser_role_holders`, `browsers_reenabled`). |
| `capture_browser`: the stub package | `uninstalled`, `not_installed_by_run`, `kept` (`--keep-capture-browser`) | `failed` | `pm path` finds nothing after `pm uninstall`. |

**Strict mode (`--strict-cleanup`) is required for Testbed** and on in the workflow
(`strict_cleanup: true`): a signed-in run with any cleanup failure ends with `result: "cleanup_failed"`
and exit 1, and a signed-in screen without the app's own exchange line fails the run (see step 6).
Without it the run still records the statuses but exits 0 on a sign-in pass.

**Server-side revocation of the app's session is proven by the app's own logout lines.** The server
has no API to list a user's sessions or revoke one by id (only `POST /auth/logout` with the
session's own refresh token, which stays inside the non-debuggable release app; an app-login
session is its own session family, so the script's web logout does not end it). So the run proves
it from the app's side: its `POST auth/logout` answered 2xx and it reported
`serverRevocation=success` (`server_logout_verified`). The script's own web session is `signed_out`
with revocation proven by the refused refresh.

Example (from a strict production run, 43644771420, credentials masked):

```json
{ "step": 7, "name": "verify-signed-in", "result": "pass",
  "detail": { "exchange": "exchanged", "exchange_log": "result=app_shell reason=exchanged", "screen": "choose_server" } },
"cleanup": {
  "capture_file": "deleted",
  "browser": "restored",
  "capture_browser": "uninstalled",
  "web_session": "signed_out",
  "app_session": "server_logout_verified",
  "web_session_refresh_check": "401 session_revoked",
  "app_sign_out_route": "Current server > server_panel:Settings > settings:Account > account:scroll > account:Sign out > confirm:Sign out",
  "app_logout_log": ["auth_logout_enter", "http method=POST path=auth/logout status=200 code=200 kind=none", "auth_logout_api_result result=success", "auth_logout_cleared serverRevocation=success"],
  "app_reported_server_logout": "success",
  "browser_role_holders": ["com.android.chrome"],
  "browsers_reenabled": []
},
"result": "signed_in",
"cleanup_ok": true,
"cleanup_failures": []
```

## Running it

```sh
# inside an emulator job (adb on PATH), after building the stub:
bash scripts/qa/capture-browser/build.sh "$RUNNER_TEMP/capture-browser.apk"
QA_LOGIN_EMAIL=… QA_LOGIN_PASSWORD=… node scripts/qa/release_auto_login.mjs \
  --apk app-release.apk --capture-browser-apk "$RUNNER_TEMP/capture-browser.apk" --env staging \
  --strict-cleanup --revoke-app-session
# locally with a Testbed QA account instead of env vars:
testbed qa-account get alpha-qa --env staging --out /tmp/qa.cred   # 0600; line 1 email, line 2 password
node scripts/qa/release_auto_login.mjs --credential-file /tmp/qa.cred --capture-browser-apk … --env staging
```

CI credentials: `QA_STAGING_TEST_EMAIL` / `QA_STAGING_TEST_PASSWORD` when set (staging runs),
else `QA_TEST_EMAIL` / `QA_TEST_PASSWORD`. The account must not have two-step verification on.

Evidence (`release-auto-login-evidence`): `summary.json`, a screenshot + UI dump per step,
`logcat-app-login.txt` (only the `SlockAppLogin` tag, masked).

## Scenarios

`--scenario scripts/qa/scenarios/<name>.mjs` (workflow inputs `scenario` + `scenario_args`) runs a
module after "verify-signed-in" and before cleanup. Its `run(ctx)` gets the device helpers
(`nodes`, `find`, `tapNode`, `shell`, `step` — each step saves a screenshot + UI dump), `option()`
for its own flags, and `ctx.web(method, path, { body, serverId })`: the QA account's web session
(refreshed on a 401, signed out in cleanup as before). It writes its result to `summary.scenario`;
a scenario that throws or returns `result: "fail"` makes the run exit non-zero after cleanup.

- `task_v2.mjs --server-name <server>` — task v2 conversations on a release build: channel Tasks
  tab → task conversation (head + messages) → reply; assignee; status Todo → In Progress → Done
  with no "In Review" offered; a Backlog task; s5: a task an old CLI created without a source (made
  once with `mode=old_cli_task`), reached from Home → its channel → its title message → its task
  entry, with a diagnostic (never failing s5) of whether the message's link opens it over a task
  page and from Home. It creates two `[QA test data, please ignore]` tasks with the QA account
  and deletes them at the end; the source messages the server posts for them stay.

```sh
gh workflow run release-auto-login.yml -f environment=production -f apk_run_id=<Android Release run> \
  -f scenario=scripts/qa/scenarios/task_v2.mjs -f scenario_args="--server-name OranixSlockTest"
```

- `artifact_reuse.mjs --server-name <server> --channel <channel> [--artifact-a pt_… --artifact-b pt_…]
  [--session-wait-min 18]` — the Artifact viewer reuse check (Hands dd109a01, #4547) on the two test
  Artifacts made once with `mode=agent_artifacts`: their cards opened A, B in turn (4 pairs), with
  a 10 s background, and once after the app logs a token refresh while the viewer page is kept
  (else NOT COVERED); each open screenshotted 5 s after the tap with whether the viewer stayed and
  which Artifact it shows. Viewer lines in `artifact-viewer-logcat.txt`. Creates and deletes nothing.

- `min_build_gate.mjs --phase banner|block|flag_off --threshold N [--server-name S]` — the minimum
  build gate (#4797, flag `mobile_min_build_android_v1`) on staging. Build A is the installed APK
  (`apk_run_id` + `apk_artifact`), build B the extra one (`extra_apk_artifact`, same run, same
  signer, higher versionCode). With `hands_serve_extra=true` the workflow serves B on Hands
  raft-android channel `debug` as an ACTIVE release scoped to the "GitHub Actions update e2e
  emulators" device group (`android_update_hands_fixture.sh publish`, hands-cli 0.5.50), checks it
  carries the x86_64 APK, and starts a short-lived joiner: the scenario writes this emulator's Hands
  installation id to a file, the joiner adds it to the group and answers ok/failed/timeout. The run
  removes it and cancels the release at the end (same single-writer concurrency group as
  android-update-e2e.yml). The Hands token is only in the Serve and Hands cleanup steps' env (and
  the joiner they start), never job-level: the emulator step (the app, the scenario) runs without it
  (`scripts/test_hands_identifier_migration.sh` pins this). Each phase first
  reads the flag through `POST feature-flags/evaluate` and stops unless it is ON with default
  variant N. `banner` (N above B): banner only, Dismiss closes it. `block` (N = B): A blocked;
  offline + background + return → banner; online again → block; A updated to B → no gate.
  `flag_off` (N = B): A blocked, then waits (up to `--wait-off-minutes`, default 40) for the flag
  admin to turn the flag OFF and checks the block goes away (read/push, else next foreground).
  `seconds_after_server_off` is reported only with `--flag-off-at-ms <epoch ms>` (when the admin
  turned it off); `seconds_after_poll_saw_off` is from the run's own poll. `features_changed` lists
  only the app's lines written after the change (that time, else the last poll that still saw ON).
  The app's `SlockMinBuildGate` / `SlockFeatureFlags` lines are in `logcat-min-build-gate.txt`.

```sh
gh workflow run release-auto-login.yml -f environment=staging \
  -f apk_run_id=<android-update-qa.yml run> -f apk_artifact=android-update-qa-a-apk \
  -f extra_apk_artifact=android-update-qa-b-apk -f hands_serve_extra=true \
  -f scenario=scripts/qa/scenarios/min_build_gate.mjs -f scenario_args="--phase block --threshold <B>"
```

## Test-data modes (no emulator)

`mode` (workflow_dispatch only) makes the fixtures the scenarios above need, once, with the QA
account's secrets inside the runner (nothing downloaded, the password never printed, every token
`::add-mask::`ed), through `scripts/qa/old_cli_task.mjs`:

- `mode=old_cli_task` — on OranixSlockTest: the external agent `qa-old-cli-task` (reused when it
  exists, created with `POST /api/agents {external: true}` otherwise), added to
  `#qa-test-create-7842`, a key minted in the runner (`POST /api/agents/:id/credentials`) into a
  temp `RAFT_PROFILE`, ONE task by `@botiverse/raft@0.0.26 task create` with no source (the server
  posts the agent's title message as its source), checked with the current CLI's `task view`.
  Not repeated when the agent already made a task with that title. `task_source_required` stops it.
- `mode=agent_artifacts` — the same agent pushes two single-file HTML Artifacts ("QA artifact A
  (可删)" / "B") with the current CLI into that channel and sends each link (the card shows once
  the link is sent; it reads the channel first, a send with unread messages is held as a draft).
  An Artifact already there is not pushed again.

Both revoke the run's key and sign the web session out at the end, and leave the agent, the task
and the Artifacts in place (the device scenarios use them). Result in the `old-cli-task-result`
artifact.

```sh
gh workflow run release-auto-login.yml -f environment=production -f mode=old_cli_task
gh workflow run release-auto-login.yml -f environment=production -f mode=agent_artifacts
```

## Caveats

- See [What a pass means](#what-a-pass-means): authorize → callback only, not the browser UX; QA
  accounts only; throwaway emulators only (the capture browser takes the browser role for the run,
  so on a shared device another test's links would be swallowed).
- Without `--revoke-app-session` the app stays signed in when the script ends (on purpose, for
  workflows that go on to test the signed-in app); its server session then lives until it expires,
  and `app_session` is `not_requested`, which `--strict-cleanup` counts as a cleanup failure.
- UI steps match English labels ("Sign in to Raft", "Sign in to a self-hosted server", "Save",
  "Drafts", "Signed in as", "Sign Out", "More", "Settings", "Account", "Sign out"); the emulator
  image is English.
- An account on several servers stops at "Choose server" (signed in, no server picked); the script does
  not pick one.
- Staging is reached through the self-hosted sheet (environment `custom` with the staging addresses),
  the path a release build offers; the internal API environment editor is not used.

## iOS (follow-up, not built)

Same shape: the app opens the login URL in `ASWebAuthenticationSession`, which a script cannot read,
so the capture would come from the simulator log or a test-only URL handler; the callback would be
delivered with `xcrun simctl openurl booted 'raft://login/callback?requestId=…&code=…'`.
