# Release build auto login (Android)

`scripts/qa/release_auto_login.mjs` signs a **release** (non-Alpha, non-debuggable) Android build in
on an emulator with a QA account, through the same browser login a person uses. The app has no QA
hook for it: the debuggable-only `QaSessionBootstrapActivity` (`mobile_login_bootstrap.mjs`) is not
in release builds, and nothing here adds one.

Workflow: **Android Release Auto Login** (`.github/workflows/release-auto-login.yml`), dispatch with
`environment` = `staging` (default) or `production`, and optionally `apk_run_id` + `apk_artifact`
to reuse a release APK instead of building one. Other emulator workflows can copy its two steps
"Build the QA capture browser" and "Sign in on the emulator".

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
6. **Verify.** Signed in = the app's `SlockAppLogin` line `app_login_complete result=app_shell` and/or
   Home ("Drafts") or "Choose server … Signed in as …" on screen, with no "Sign in to Raft". Any other `app_login_complete` result fails
   the run.
7. **Clean up (always).** The script signs its own web session out (`POST /api/auth/logout`, what the
   web's Sign out sends; the app's session is separate), deletes the capture file, gives the browser
   role back to the previous holder and uninstalls the capture browser if this run installed it.
   Signing the app out is not part of it.

## Guarantees

| Requirement | How |
|---|---|
| No server check is bypassed | Only the endpoints the web page calls, with the same bodies and a bearer token; there is no CSRF token or captcha on these routes (the web client uses bearer tokens, not cookies). Two-step verification (`secondFactorRequired`, or `second_factor_step_up_required` on approve) and HTTP 429 from the auth rate limiter **stop the run**; nothing is retried in a loop. |
| PKCE and state stay bound to this app session | The script never builds an authorize URL: it only reads the one the app opened. The verifier never leaves the app. The captured URL must be `https://<expected web host>/login/app` with exactly one `request`; the approval's redirect must be `<app scheme>://login/callback` with exactly `requestId` + `code` and the **same** `requestId` (the flow's state), else the run stops before anything reaches the app. |
| Code / callback only to this app, never logged | Explicit-package `am start`, sent on adb's stdin (not the host command line) with its output discarded. Email, password, tokens, request id, code and callback URL are masked in every log line, the summary and the logcat excerpt; no screenshot is taken while the code exists outside the app. |
| Cleanup on exit | `finally`: web session signed out, capture file removed, browser role restored / disabled browsers re-enabled, capture browser uninstalled (unless `--keep-capture-browser`). Recorded under `cleanup` in `summary.json`. |

## Running it

```sh
# inside an emulator job (adb on PATH), after building the stub:
bash scripts/qa/capture-browser/build.sh "$RUNNER_TEMP/capture-browser.apk"
QA_LOGIN_EMAIL=… QA_LOGIN_PASSWORD=… node scripts/qa/release_auto_login.mjs \
  --apk app-release.apk --capture-browser-apk "$RUNNER_TEMP/capture-browser.apk" --env staging
# locally with a Testbed QA account instead of env vars:
testbed qa-account get alpha-qa --env staging --out /tmp/qa.cred   # 0600; line 1 email, line 2 password
node scripts/qa/release_auto_login.mjs --credential-file /tmp/qa.cred --capture-browser-apk … --env staging
```

CI credentials: `QA_STAGING_TEST_EMAIL` / `QA_STAGING_TEST_PASSWORD` when set (staging runs),
else `QA_TEST_EMAIL` / `QA_TEST_PASSWORD`. The account must not have two-step verification on.

Evidence (`release-auto-login-evidence`): `summary.json`, a screenshot + UI dump per step,
`logcat-app-login.txt` (only the `SlockAppLogin` tag, masked).

## Caveats

- It covers the real **authorize → callback** path (app-login start, approval, redirect to the app,
  PKCE exchange, session), **not the browser UX**: no web page is rendered, so the login form, the
  confirm page's look, Chrome Custom Tab/browser behaviour and the browser → app hand-off prompt are
  not tested.
- The capture browser takes the browser role for the run; on a shared device another test that opens
  links in that window sees them swallowed.
- UI steps match English labels ("Sign in to Raft", "Sign in to a self-hosted server", "Save",
  "Drafts", "Signed in as"); the emulator image is English.
- An account on several servers stops at "Choose server" (signed in, no server picked); the script does
  not pick one.
- Staging is reached through the self-hosted sheet (environment `custom` with the staging addresses),
  the path a release build offers; the internal API environment editor is not used.

## iOS (follow-up, not built)

Same shape: the app opens the login URL in `ASWebAuthenticationSession`, which a script cannot read,
so the capture would come from the simulator log or a test-only URL handler; the callback would be
delivered with `xcrun simctl openurl booted 'raft://login/callback?requestId=…&code=…'`.
