# Release pre-login for ordinary Testbed cases (contract, proposal)

## Status: NOT RUNNABLE

**Not runnable today, and refused by design.** Argus reviewed PR #4946 over three rounds: not
approved, no staging run authorized. The Testbed side of this contract is **not implemented**:
- the claim does not return `release_prelogin` binding fields;
- there is no admission for a release-prelogin mode;
- attempt / nonce minting, dispatch and retry handling do not exist.

So every request is refused at `plan` (`spec_not_bound_by_testbed_claim`).

In addition, nothing can reach a real sign-in or a product PASS:
- no Mobile allow-list entry is `compatible`;
- no pre-login credential is wired;
- capture coverage can never be proven with today's probes, so a PASS is always reported
  `incomplete`.

Everything below describes the gated code and the contract it expects. It does not describe a
working path.

## Why

Testbed acceptance cases on a release build stop at "login phase UNKNOWN". The CI login phase
types into a password form, and a release build signs in through the browser. The release
auto-login (#218, [release-auto-login.md](release-auto-login.md)) does that browser login. This
contract wraps it as a pre-login for ordinary Testbed cases, with receipts that claim no more than
they prove.

## Builds and bytes

- **The readiness receiver is compiled out of store builds.**
  - Its code is in `compose/androidApp/src/qaReadiness/kotlin`. Only `debug` (so PR CI compiles
    and unit-tests it) and the QA-instrumented build type `qaRelease` compile it, through
    `variant.sources.kotlin`.
  - Only `src/qaRelease/AndroidManifest.xml` declares it.
- **Merged-manifest checks run in PR CI and before every assemble/bundle of each variant:**
  `verifyReleaseHasNoQaReadinessReceiver`, `verifyPlayHasNoQaReadinessReceiver` and
  `verifyQaReleaseHasQaReadinessReceiver` (`test.yml`).
- **Packaged-content check, dispatch-only.**
  `.github/workflows/qa-release-candidate.yml`:
  - builds `release` and `play` (APK + AAB) and `qaRelease` (APK);
  - runs `scripts/qa/check_qa_readiness_packaging.py` on the built bytes: every dex and every
    manifest (binary XML or protobuf, UTF-8 and UTF-16LE). Store artifacts must contain no
    receiver class, descriptor or marker. The candidate must contain the class, and `aapt` must
    show the receiver `enabled=false`, `exported=true`, `permission=android.permission.DUMP`. An
    artifact it cannot inspect fails;
  - signs the candidate with a throwaway key made in the job and checks it is not debuggable;
  - records `build-info.json`: sha256, package, version, signer cert sha256, commit and run.

  It publishes nothing; the candidate is only a 7-day workflow artifact. It is not part of PR CI
  because three R8 builds are too heavy per PR, and it is not scheduled. It has **not been run
  yet**.
- **What this is not.**
  - The `qaRelease` candidate is QA-modified bytes with a throwaway signer, not the store bytes.
  - These checks are not acceptance of a raw artifact a store later serves.
  - Shipping the receiver in a real store build would need a separate product and security
    approval.

## Who decides, and the allow-list

- **Testbed admission is authoritative.** Mobile can only narrow it.
  `scripts/qa/release_prelogin_allowlist.json` pins an exact `(case id, revision, case sha256,
  platform)`.
- **Promotion to `compatible` needs a joint Mobile + Testbed review** of:
  - the exact revision, spec and goal digests;
  - the environment and target;
  - side effects, including read state;
  - the compatible artifact.

  Neither a caller tag, a case YAML field, a repo id nor green CI authorizes it.
- **`channel-switch-navigation` r3** (sha256 `0c62efd8…b95c`) stays `candidate`.
  - It is still `auth_fixture: alpha-qa`; do not retag it to get past admission.
  - Opening channels changes read state, so it is not zero-write.
  - It needs two distinguishable channels.
- **Never switched**, whatever any spec says:
  - real login-UI cases;
  - logout;
  - upgrade;
  - cross-server (multi-server / Android + iOS);
  - bootstrap canary;
  - server create;
  - cases tagged into those classes (flow or block `tags:` list);
  - `auth_fixture` `none` or missing;
  - YAML forms the strict reader does not support.

  Debuggable/Alpha builds and bootstrap cases are refused, and `aapt` must succeed.

## Binding (Testbed-frozen spec → claim → attempt → run)

1. **Spec.** Testbed passes the spec (workflow input `release_prelogin`, contract
   `testbed-release-prelogin-spec/v1`). It holds:
   - the run case;
   - the attempt id + nonce, which Testbed mints and stores before dispatch;
   - case id / revision / platform / case sha256 / goal sha256 / `auth_mode: release-prelogin`;
   - the frozen target: env, account id, server id, slug, name;
   - the artifact sha256 and badging.
2. **Claim binding.** `stamp_report.sh claim` deletes the claim response after use. From the
   authenticated response it keeps **only** `{contract, run_case_id, spec_sha256, attempt_id,
   ci_job_ref}`, in `$RUNNER_TEMP/stamp-release-prelogin-binding-<run case>.json` (mode 0600).
   It never keeps the token or the response, and it writes nothing when the fields are absent or
   malformed (the case today).
3. **`plan`** requires that binding for this run case **and** this CI job ref, with
   `spec_sha256 = sha256(canonical spec)` and the same attempt id. It also checks:
   - the goal sha256 (`inputs.goal`);
   - the artifact (APK sha256 = spec = `artifact_sha256` input; package / version via `aapt`);
   - the case bytes;
   - the allow-list.

   It mints nothing. It writes `attempt.json`, which is uploaded and holds digests only:
   `account_user_ref`, `server_ref`. The cleartext target (server name for the in-memory pick)
   goes only to `$RUNNER_TEMP/qa-prelogin-private/target.json` (0600).
4. **Report gate.** `report-gate` accepts receipts only when `attempt.json` and `outcome.json`
   name this run case and this workflow run **and** run attempt. Internally consistent receipts
   from another run or rerun are rc 3.

## Login window (only reachable once Testbed binds a spec and a credential is wired)

| Step | What |
|---|---|
| intent | `login-started.json` (attempt id, workflow run, time) before any device action. Requires `QA_PRELOGIN_EMAIL/PASSWORD`; without them it stops before touching the device (`prelogin_credentials_not_configured`). |
| capture probes | Recorder stopped and confirmed gone; device marker `/data/local/tmp/qa-prelogin-window`; async `pidof screenrecord` poll every 1 s for the whole window. |
| receipt writer | `pm enable` the receiver, read back `enabled` (failed read: stop). |
| sign in | `release_auto_login.mjs --no-capture --defer-app-session --strict-cleanup --account-ref-nonce <nonce>`. UI trees via `uiautomator dump /dev/tty` and login logs via `logcat -d`, both into memory; no screenshots; summary only into the private dir. **One deliberate file exception:** the capture browser stub (below). |
| server pick | Frozen server name from the private target, tapped from an in-memory UI tree. |
| readiness | The app's receipt for a fresh request id (binding rules below). |
| end of window | Stop the poll, scan `/sdcard` (excluding `Android/`) for files newer than the marker, read the recorder once more. |
| login record | Only when capture is `not_observed` and callback + readiness are `ok`: `login-record.json` (`release-prelogin`, `login_end` = receipt stamp). Otherwise unknown: no agent, no evidence. |

### The capture browser's private login-URL file (the one file the login writes)

The stub (`scripts/qa/capture-browser`) receives the app's pending login URL and writes it,
atomically and fsynced, to **its own app-private** `files/captured_url`. Only `run-as` on this
debuggable stub (adb) and the stub itself can read it. A non-persistent transport would mean
changing and re-validating the stub on a device, which is not possible in this scope. The
protections instead:
- the sign-in run reads the file, deletes it **before** validating the URL (so a broken or foreign
  link is deleted too), and verifies it is absent in its own cleanup;
- the attempt's cleanup step, a separate process that also runs when the sign-in run was killed
  after the request arrived, deletes the file and reads its absence back, then uninstalls the stub
  (which removes its private data dir);
- `local_cleanup.items.capture_browser_file` is `ok` only when the stub is read back absent or the
  file is read back deleted; otherwise `unknown`.

If the whole emulator step dies, nothing reads the device back: unknown. On a throwaway emulator
the file disappears with the emulator, but that is not proven by a receipt.

### Capture: what is and is not covered (bounded probes)

The capture receipt reports `not_observed` (or `unknown`), **never "off"**.

- **Declared and probed:**
  - `screenrecord` processes, sampled every 1 s;
  - files on shared storage newer than the window marker, excluding `Android/`, scanned once at
    the end;
  - the harness's own adb capture calls;
  - the sign-in run's screenshot count.
- **Not covered:**
  - recorders that start and stop between samples;
  - files created and deleted within the window;
  - `/sdcard/Android/`;
  - app-private storage;
  - vendor or system capture;
  - host-side capture;
  - other processes' memory.
- **Late failures:** capture faults after the login window (during the case) are outside this
  receipt.

`coverage_proven` is always `false` today, so **capture never by itself allows a product PASS**.
A pass needs the controlled capture sources declared up front **and proven covered** for the whole
window. Until a design for that exists (for example an owned emulator image with no other capture
producers, plus continuous kernel-level evidence), every PASS is reported `incomplete`
(`capture_coverage_not_proven`). The file scan reports only a count, never names.

## The app's readiness receipt (`raft-qa-release-readiness/v2`, qaRelease only)

The receipt carries:
- attempt id, nonce and request id;
- package and version;
- pid and `/proc` start ticks;
- `signed_in`;
- `user_ref = sha256("raft-qa-readiness-user/v1" NUL nonce NUL user id)`;
- the stored selected server id + slug (logcat only, read by adb).

The runner turns the server into
`server_ref = sha256("raft-qa-readiness-server/v1" NUL nonce NUL id NUL slug)` and keeps only that
digest in receipts. Both refs are minimisation for equality checks, not signatures and not
anonymity.

**Binding rules** (else `unknown`):
1. The installed build was read fully and equals the frozen artifact.
2. The process was read before and after the request and is the same.
3. Exactly one line answers this attempt + nonce + request id (no stale or replayed line).
4. The writer pid and the receipt's process match.
5. It is signed in, and `user_ref` and `server_ref` equal the frozen ones.
6. The build matches.
7. No sensitive-looking field.

It is a bounded signal, not an attestation.

## Cleanup: three separate records

| Record | Values |
|---|---|
| `local_cleanup` (`complete` / `unknown` / `not_started`) | Each item positively read back: `capture_file`, `capture_browser_file`, `browser`, `capture_browser`, `app_signed_out_on_device`, `receipt_writer`. A killed sign-in run (no summary) is never `complete`. |
| `web_session` | From the sign-in run's explicit `web_login` state (not_initiated → initiated → succeeded / confirmed_rejected / unknown). `signed_out_verified` (refresh refused after logout), `not_initiated` (no request sent), `login_rejected` (clear 4xx / 429), else **`unknown`**. A login sent with a lost, 5xx or malformed answer is `unknown`, never "not opened". |
| `app_server_revocation` | `app_reported_2xx` (the app's own bounded report; never "revoked"), `no_app_session` **only** when the sign-in run recorded `callback_delivery: not_attempted`, else `unknown`. |

Only this attempt's own sessions are signed out. There is never a global sign-out of the shared
account's other sessions, and no new server API.

## Outcomes

| Condition (in order) | Outcome | rc |
|---|---|---|
| not eligible | `not_run` | 3 |
| capture not `not_observed`, callback or readiness not `ok` | `incomplete` | 3 |
| agent rc 1 + verdict `fail` | `fail` (product fail after verified preparation; capture assurance + cleanup as notes) | 1 |
| agent rc/verdict anything but (0, `pass`) | `incomplete` | 3 |
| pass, capture coverage not proven (**always today**) | `incomplete` | 3 |
| pass, local cleanup not complete / web session not verified | `incomplete` | 3 |
| pass, all of the above proven | `pass` | 0 |

"Report verdict" takes its rc only from `report-gate`. The gate recomputes from the **sealed**
receipts and the current agent result and binds the run case + run + rerun; anything else is 3
(harness).

## Receipts and the upload boundary

- `finalize` (always, after the emulator step):
  - records `cleanup_not_reached` if needed;
  - recomputes the outcome;
  - deletes everything from `qa-prelogin/` that is not an allow-listed file passing its **strict
    per-file schema** (every key listed, every value validated; extra fields refused);
  - copies the checked files into a **fresh** read-only `qa-prelogin-sealed/` with a
    `MANIFEST.sha256`.

  If any file fails, only an `incomplete` outcome is sealed.
- `verify-sealed` checks the exact file set, hashes and schemas again. Only then is
  `qa-prelogin-sealed/` uploaded.
- Later writes to `qa-prelogin/` never reach the artifact.
- The schemas are an allow-list, not a general secret scanner. Each write is also checked against
  the secret values the writing step has.

## Credentials (TODO, not wired)

Only `QA_PRELOGIN_EMAIL/PASSWORD`, which no workflow sets. Staging needs a separately reviewed
staging-only credential whose subject is the frozen account, scoped to the one child process.
`QA_STAGING_TEST_*` wiring needs its own security review. `QA_TEST_*` is never used here.

## Follow-ups (not runnable until done)

1. **Testbed:**
   - claim fields `release_prelogin {spec_sha256, attempt_id}`;
   - admission for a release-prelogin mode;
   - minting and storing the attempt id + nonce before dispatch;
   - rerun / retry / old-worker policy.
2. **A capture-coverage design** that can prove the declared sources were covered; until then no
   PASS.
3. **A non-persistent login-URL transport** for the capture browser, validated on a device, if
   the protected private file is not accepted.
4. **First authorized run of `qa-release-candidate.yml`**, and how its identity is frozen for
   Testbed.
5. **Staging credential review.**
6. **Joint review before any allow-list promotion.**

No staging run is authorized.

## iOS (plan only)

A QA-only build configuration compiling a URL handler in (store builds compile it out), with the
same fields through `os_log`, read via `simctl log show`. There is no physical device and no
`commonMain` change.
