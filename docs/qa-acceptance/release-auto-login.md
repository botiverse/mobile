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
| `app_session`: the app's own session (`--revoke-app-session`) | `signed_out_via_app`, `not_requested`, `not_signed_in` | `local_cleared_server_unconfirmed`, `failed` | The app's own Sign out (Choose server → **Sign Out**, or More → Settings → Account → **Sign out** → confirm **Sign out**), then the app must be back on "Sign in to Raft" / "Sign in through …". The app sends `POST /api/auth/logout` with its refresh token itself; `app_reported_server_logout` records the app's own report of that call when it reaches logcat (`success`, or `not_logged`). If the UI route fails, `pm clear build.raft.app` wipes the app's tokens: `local_cleared_server_unconfirmed` (the server session lives on until it expires). `app_sign_out_route` / `app_sign_out_error` say what happened. |
| `capture_file`: the captured login URL | `deleted`, `not_applicable` | `failed` | Removed, then checked absent with `run-as`. |
| `browser`: the default browser | `restored`, `not_changed` | `failed` | Browser role back with the previous holder(s) and not with the stub; every browser disabled for the run is enabled again (`browser_role_holders`, `browsers_reenabled`). |
| `capture_browser`: the stub package | `uninstalled`, `not_installed_by_run`, `kept` (`--keep-capture-browser`) | `failed` | `pm path` finds nothing after `pm uninstall`. |

**Strict mode (`--strict-cleanup`) is required for Testbed** and on in the workflow
(`strict_cleanup: true`): a signed-in run with any cleanup failure ends with `result: "cleanup_failed"`
and exit 1, and a signed-in screen without the app's own exchange line fails the run (see step 6).
Without it the run still records the statuses but exits 0 on a sign-in pass.

**Server-side revocation is not independently verified for the app's session.** The server (slock
staging) has no API to list a user's sessions or revoke one by id: only `POST /auth/logout` with the
session's own refresh token, which stays inside the non-debuggable release app. So the strongest
statuses are `signed_out_via_app` (the app ran its own sign-out, which revokes on the server, and is
back on its sign-in page) and, for the script's own web session, `signed_out` (revocation proven by
the refused refresh). There is no `server_revoked` status for the app session until such an API
exists.

Example (from a strict staging run, credentials masked):

```json
{ "step": 7, "name": "verify-signed-in", "result": "pass",
  "detail": { "exchange": "exchanged", "exchange_log": "result=app_shell reason=exchanged", "screen": "choose_server" } },
"cleanup": {
  "capture_file": "deleted",
  "browser": "restored",
  "capture_browser": "uninstalled",
  "web_session": "signed_out",
  "app_session": "signed_out_via_app",
  "web_session_refresh_check": "401 session_revoked",
  "app_sign_out_route": "choose_server:Sign Out",
  "app_reported_server_logout": "not_logged",
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
  with no "In Review" offered; a Backlog task; a task an old CLI created without a source, opened
  from its title message. It creates two `[QA test data, please ignore]` tasks with the QA account
  and deletes them at the end; the source messages the server posts for them stay.

```sh
gh workflow run release-auto-login.yml -f environment=production -f apk_run_id=<Android Release run> \
  -f scenario=scripts/qa/scenarios/task_v2.mjs -f scenario_args="--server-name OranixSlockTest"
```

## Caveats

- See [What a pass means](#what-a-pass-means): authorize → callback only, not the browser UX; QA
  accounts only; throwaway emulators only (the capture browser takes the browser role for the run,
  so on a shared device another test's links would be swallowed).
- Without `--revoke-app-session` the app stays signed in when the script ends (on purpose, for
  workflows that go on to test the signed-in app); its server session then lives until it expires.
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
