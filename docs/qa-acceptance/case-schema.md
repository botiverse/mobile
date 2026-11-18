# Raft QA Acceptance — Case Schema v0

## Overview

Each acceptance case is a YAML file describing a structured test scenario.
The executor agent (M3 in CI) reads the case, performs the steps using
android-use CLI tools, captures evidence, and outputs a verdict.

## Schema

```yaml
# Required metadata
id: string                    # Unique case identifier (kebab-case)
title: string                 # Human-readable title
app: string                   # App slug (e.g. raft-android)
tier: L1 | L2 | L3            # Execution tier
version: string               # Schema version (currently "0.1")

# Optional metadata
description: string           # What this case verifies and why
tags: [string]                # Tags for filtering (e.g. login, messaging, regression)
baseline_ref: string          # Visual baseline key for screenshot assertions
timeout_seconds: int          # Max execution time (default: 120)
max_retries: int              # Retry on flaky failure (default: 2)
auth_fixture: string          # Canonical bootstrap fixture; `none` disables bootstrap
auth_mode: string             # release-prelogin for release-build cases (see below)
launch_route: string          # Route Stamp verifies after canonical bootstrap
build_variant: alpha | release  # Default alpha. `release`: see "Release-build cases" below
app_package: string           # Android package under test (release: build.raft.app)
read_only: bool               # The case changes no account/server state
forbidden_screens:            # Screens the run must never show (see below)
  - id: string
    why: string
    markers: [string]         # Exact on-screen texts that identify the screen
on_forbidden_screen: string   # What happens if one is shown (release: run invalid)

# Device requirements
device:
  type: emulator | cloud-phone  # Execution backend
  api_level: int                # Android API level (e.g. 34)
  locale: string                # e.g. en-US, zh-CN

# Environment
environment:
  server: string               # Server URL or slug (e.g. staging)
  credentials_ref: string      # Reference to QA account (resolved by executor, never inline)

# Preconditions (steps that must be true before starting)
preconditions:
  - type: app_installed
    params: { package: build.raft.app }
  - type: logged_out           # Ensure fresh state

# Cleanup (steps to run after the case, regardless of pass/fail)
cleanup:
  - action: stop_app
  - action: clear_data          # Optional: clear app data for reproducibility

# Steps — sequential operations the agent executes
steps:
  - id: string                 # Step identifier (for assertion references)
    action: launch | open_page | tap | input | swipe | key | wait | screenshot | dump_layout | logcat
    params: object             # Action-specific parameters (see below)
    description: string        # What this step does and why
    capture: [screenshot | dump_layout | logcat]  # Evidence to collect after this step
    on_failure: continue | abort  # Default: abort

# Assertions — what must be true for the case to pass
assertions:
  - id: string
    type: screenshot_similarity | element_exists | element_visible | text_match | text_not_visible | no_crash | logcat_clean
    params: object             # Assertion-specific parameters
    after_step: string         # Step ID after which to evaluate this assertion
    severity: critical | major | minor  # Impact level if assertion fails
    description: string

# Expected outcome
expected:
  result: pass | fail | known_issue
  notes: string                # Context for known issues
```

## Release-build cases

A case with `build_variant: release` runs on the release build (Android
package `build.raft.app`), not Alpha. The release build has no QA session
bootstrap (`QaSessionBootstrapActivity` is debuggable-only), so:

- `auth_fixture` must be `none`; never `alpha-qa`.
- `auth_mode` must be `release-prelogin`: the app is signed in before capture
  starts. Testbed recognizes it and accepts the case into source; executors
  that do not know it refuse the case before registration/dispatch.
- The runner signs the app in through the product's own login **before** the
  agent, the recorder or any capture starts (Android:
  `scripts/qa/release_auto_login.mjs`, see `release-auto-login.md`). The agent
  never signs in. `environment.credentials_ref` names the QA account.
- Release cases live in `docs/qa-acceptance/release-build/`, not `cases/`:
  `cases/` is the frozen Alpha release set, and
  `scripts/qa/check_release_acceptance_cases.py` rejects any case there that
  is not `alpha-qa` or not listed in `release-1.7.0-android.json`.

**Forbidden screens.** The evidence gate (`scripts/qa/secure_evidence.mjs`)
only scans and redacts text files; it never looks at screenshots or video.
A release run is signed in to a real account, so any screen that shows
account information (Settings > Account shows the email; Choose server shows
"Signed in as <email>") must never be screenshotted or recorded. A case lists
those screens in `forbidden_screens`. If a run shows one anyway, even by a
mis-tap, the run is **invalid**: its screenshots and recording are deleted,
nothing is uploaded, and it is re-run. It is not reported as pass or fail,
and keeping the media with a note is not allowed.

**Frames reach the model before the gate.** The executor sends the screen
after each action to the model in the next turn, BEFORE the final evidence
gate runs. Deleting media at the end therefore does not keep a forbidden
screen away from the model. Required executor behaviour (not implemented
yet): identify the page before sending a frame to the model; if the frame is
a forbidden screen, or the page cannot be identified, void the whole capture
(screenshots and recording) and block upload.

Detection is a manual / agent rule today, not automatic. The natural hook is
the evidence gate, which already has the "delete everything, upload nothing"
path, but no case data reaches it (it only gets `--dir`), and the executor
does not keep a UI dump per turn, so a text check there could not see a
visit that left only images. An automatic check would need both: the case's
`forbidden_screens[].markers` passed to the gate, and a per-turn UI dump to
match them against.

## Action Reference (android-use CLI mapping)

| Action | CLI Command | Key Params |
|--------|-------------|------------|
| `launch` | `android-use launch` | `--component` (optional) |
| `open_page` | `android-use open-page <page_name>` | `page_data` (JSON) |
| `tap` | `android-use tap <x> <y>` | x, y (pixels) |
| `input` | `adb shell input text` | text, field_selector |
| `swipe` | `android-use swipe --direction <dir>` | direction: up/down/left/right |
| `key` | `android-use key <key_name>` | key_name: BACK, HOME, ENTER |
| `wait` | (sleep) | duration_ms |
| `screenshot` | `android-use screenshot --out <dir>` | prefix |
| `dump_layout` | `android-use dump-layout --out <dir>` | prefix |
| `logcat` | `android-use logcat --duration <s> --out <dir>` | filter_regex |

## Assertion Types

| Type | Description | Key Params |
|------|-------------|------------|
| `screenshot_similarity` | Compare screenshot to visual baseline | baseline, threshold (0-1.0) |
| `element_exists` | Check element exists in uiautomator XML | resource_id, text, class |
| `element_visible` | Check element exists AND is visible | resource_id, text |
| `text_match` | Match text content of an element | resource_id, expected_text |
| `text_not_visible` | Verify text is NOT on screen | text |
| `no_crash` | Check logcat for crash signatures | filter_regex |
| `logcat_clean` | Check logcat for error patterns | error_patterns |

## Verdict Output (produced by executor)

```json
{
  "case_id": "login-email-password",
  "verdict": "pass | fail | error | flaky",
  "executed_at": "2026-07-16T17:30:00Z",
  "duration_seconds": 45,
  "steps_executed": 6,
  "assertions": [
    {
      "id": "home-visible",
      "type": "element_visible",
      "result": "pass | fail",
      "evidence": "screenshots/step-6-home.png"
    }
  ],
  "evidence": [
    { "type": "screenshot", "path": "screenshots/step-3-after-login.png", "step": 3 },
    { "type": "screenshot", "path": "screenshots/step-6-home.png", "step": 6 },
    { "type": "dump", "path": "dumps/step-6-home.xml", "step": 6 },
    { "type": "logcat", "path": "logs/full-logcat.txt", "step": "all" }
  ],
  "m3_judgments": [
    { "panel": 1, "verdict": "pass", "confidence": 0.92, "reasoning": "..." },
    { "panel": 2, "verdict": "pass", "confidence": 0.88, "reasoning": "..." },
    { "panel": 3, "verdict": "fail", "confidence": 0.71, "reasoning": "..." }
  ],
  "m3_consensus": "pass | fail | disputed",
  "error": null
}
```

## Asymmetric Judgment Panel Rules

### M3 Panel (3 independent instances)

**Consensus rules:**
- **FAIL consensus** (≥2/3 vote fail): Case is sent to human review as **likely fail**
- **PASS consensus** (3/3 vote pass): Case is sent to human review as **likely pass**
- **PASS with dissent** (2/3 pass, 1/3 fail): Case is sent to human review as **disputed — requires careful review**
- **No consensus** (all different): Case is automatically **flaky**, retried up to max_retries

### Human Review (final verdict)

- Agent **never** self-approves. M3 verdict is advisory only.
- **Disputed** cases get priority in review queue (sorted: disputed > fail > pass)
- **Pass** requires higher confidence than fail — asymmetric by design
- Reviewer can override any M3 verdict

### Severity Weighting

- **Critical** assertion fail = case fails immediately (no need to check others)
- **Major** assertion fail = case likely fails, but check all assertions for full picture
- **Minor** assertion fail = case can still pass, logged as warning
