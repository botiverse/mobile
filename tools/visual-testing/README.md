# Slock Compose Visual Testing

Cross-platform visual testing CLI for Slock Compose / KMP UI components.

This tool lives in the Slock Compose Android repository so the upstream Slock
web repo can stay clean and track upstream. It owns the portable
visual-testing boundary:

- case manifest loading and validation
- provider output contract
- state variant schema
- raw image diff metrics
- side-by-side compositor
- HTML report generation

The user-facing providers are `react`, `android`, and `harmony`. Providers render real components from the manifest and fixtures, then write raw screenshots and metadata under `visual-testing-results/<provider>/`. If Android or Harmony renders KMP/shared UI, record that as the provider `source`; do not expose `kmp-shared` as a fourth platform.

Default runs compare only canonical real-provider captures. The CLI no longer
generates built-in demo provider images for missing Android/Harmony captures;
non-React providers must be produced by a real capture command and stale
`providerType: "builtin-demo"` artifacts are ignored by diff.

The CLI defaults to:

- tool / artifacts repo: the current Slock Compose Android checkout
- React source repo: `third_party/slock-source` inside the current Slock
  Compose Android checkout

Initialize the pinned React source before running commands that inspect React
components:

```bash
git submodule update --init third_party/slock-source
```

Override the React source repo only for one-off debugging:

```bash
node tools/visual-testing/src/cli.mjs scan --react-repo-dir ~/0Workspace/github.com/botiverse/slock
SLOCK_REACT_REPO_DIR=~/0Workspace/github.com/botiverse/slock node tools/visual-testing/src/cli.mjs capture --providers react
```

If the default `third_party/slock-source` checkout is missing or does not
contain the React visual provider harness, commands fail with a clear error
instead of silently falling back to another local web checkout. Canonical
visual alignment should use the pinned `third_party/slock-source` revision.

## CLI

```bash
node tools/visual-testing/src/cli.mjs scan
node tools/visual-testing/src/cli.mjs validate
node tools/visual-testing/src/cli.mjs token-audit
node tools/visual-testing/src/cli.mjs capture --providers react
node tools/visual-testing/src/cli.mjs capture --providers android --command './scripts/capture-android-visuals.sh'
node tools/visual-testing/src/cli.mjs diff --baseline react --current android
node tools/visual-testing/src/cli.mjs report --baseline react --current android
node tools/visual-testing/src/cli.mjs site --baseline react --current android
node tools/visual-testing/src/cli.mjs publish-gh-pages --baseline react --current android --dry-run
node tools/visual-testing/src/cli.mjs publish-gh-pages --baseline react --current android --repo-dir ~/0Workspace/github.com/bytemain/slock-android
```

The Android repo keeps `scripts/slock-visual-testing.mjs` as a compatibility shim.

## Capture Taxonomy

Visual testing is split into two explicit suites:

- `components.*` cases are component fixture captures. They may use deterministic
  mock fixture state, but both providers must render the real product
  component/code path. These cases use `captureType: "component-fixture"` and
  appear under **Components Parity** in the generated report.
- `screens.*` cases are full real-screen captures. They must launch the real
  product page/route/screen container on device, not a purpose-built visual
  fixture page. These cases use `captureType: "real-screen"` and appear under
  **Screens Parity** in the generated report.

The CLI infers `real-screen` for ids prefixed with `screens.` and
`component-fixture` otherwise. Existing legacy case ids such as
`components.home.activity.results` are therefore treated as component fixture captures
until they are deliberately renamed or replaced. New cases should use the
taxonomy prefix from the start:

```json
{
  "id": "components.thread.message-row",
  "captureType": "component-fixture"
}
```

```json
{
  "id": "screens.thread.channel",
  "captureType": "real-screen"
}
```

Generated `diff`, `metadata`, `component-matrix`, HTML, and `llms.txt` output
include `captureType` so downstream summaries can distinguish fixture parity
from true app-screen parity.

Component providers should also emit stable style probes in their metadata JSON
when typography or design-token parity matters. The report reads these optional
fields and shows them beside the screenshots:

```json
{
  "typography": [
    {
      "selector": "[data-testid='message-body']",
      "text": "Hello @Cindy",
      "fontFamily": "Inter",
      "fontSize": 14,
      "fontWeight": 400,
      "lineHeight": 20,
      "letterSpacing": 0,
      "color": "#141111"
    }
  ],
  "styleTokens": [
    {
      "selector": "[data-testid='message-card']",
      "backgroundColor": "#FFFFFF",
      "borderColor": "#141111",
      "shadow": "3px 3px 0 #141111"
    }
  ]
}
```

Use stable selector/component labels rather than pixel coordinates. Keep this
metadata out of the image itself; it belongs in provider metadata and the report
case card so typography, size, weight, color, and token differences are
machine-readable.

## Android / KMP Connected Captures

Most Android baselines should be captured through the dedicated KMP visual pages,
not by manually navigating the app to an equivalent state. The connected test
entry point is:

```text
compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt
```

Each test method launches `SlockKuiklyActivity` with a `compose_*_visual` page
and deterministic `pageData`, captures the app window, crops/normalizes to dp
pixels, validates that the image is non-blank, and writes both PNG and metadata.

KMP visual fixture pages are build-gated. Normal debug/release builds do not
compile or register `compose_*_visual` routes. Any connected visual capture
Gradle invocation must opt in with either `-PslockVisualFixtures=true` or
`SLOCK_VISUAL_FIXTURES=true`; prefer the Gradle property in checked-in commands.

Run a focused case from the Android repo:

```bash
cd compose
ANDROID_SERIAL=emulator-5560 ./gradlew --no-daemon --console=plain \
  -PslockVisualFixtures=true \
  :androidApp:connectedConnectedTestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=build.raft.app.visual.KmpVisualScreenshotCaptureTest#captureThreadMessageRow
```

Normal product builds intentionally omit the flag:

```bash
cd compose
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin
./gradlew :androidApp:assembleDebug
```

The shared Gradle task `:shared:verifyNoProductionVisualPages` enforces this
boundary by failing if production source sets contain visual `@Page`
registrations.

Artifacts are mirrored for `adb pull` here:

```text
/sdcard/Download/slock-visual-testing/android/<caseId>.png
/sdcard/Download/slock-visual-testing/android/<caseId>.metadata.json
```

Copy both files into the visual-testing result tree before diffing:

```bash
adb -s emulator-5560 pull \
  /sdcard/Download/slock-visual-testing/android/components.thread.message.row.png \
  ../visual-testing-results/android/
adb -s emulator-5560 pull \
  /sdcard/Download/slock-visual-testing/android/components.thread.message.row.metadata.json \
  ../visual-testing-results/android/
```

Use the built-in KMP visual-page path first for these surfaces:

| Case | Connected test method | KMP visual page |
| --- | --- | --- |
| `components.tokens.palette` | `captureTokenPalette` | `compose_tokens_palette_visual` |
| `components.navigation.tabbar.states` | `captureNavigationTabbarStates` | `compose_bottom_tabbar_visual` |
| `auth.login.inputs.empty` | `captureAuthLoginInputsEmpty` | `compose_auth_login_inputs` |
| `auth.login.inputs.filled` | `captureAuthLoginInputsFilled` | `compose_auth_login_inputs` |
| `auth.login.inputs.invalid` | `captureAuthLoginInputsInvalid` | `compose_auth_login_inputs` |
| `components.auth.register.inputs` | `captureAuthRegisterInputs` | `compose_auth_register_inputs` |
| `components.thread.message.row` | `captureThreadMessageRow` | `compose_thread_message_row_visual` |
| `components.thread.composer.empty` | `captureThreadComposerEmpty` | `compose_thread_composer_visual` |
| `components.thread.composer.states` | `captureThreadComposerStates` | `compose_thread_composer_visual` |
| `components.thread.composer.as-task-selected` | `captureThreadComposerAsTaskSelected` | `compose_thread_composer_visual` |
| `components.home.titlebar.states` | `captureHomeTitlebarStates` | `compose_home_titlebar_visual` |
| `components.home.notification-center.states` | `captureHomeNotificationCenterStates` | `compose_home_notification_center_visual` |
| `components.home.search.results` | `captureHomeSearchResults` | `compose_home_search_visual` |
| `components.home.search.channel-dropdown` | `captureHomeSearchChannelDropdown` | `compose_home_search_channel_dropdown_visual` |
| `components.home.saved.results` | `captureHomeSavedResults` | `compose_home_saved_visual` |
| `components.home.activity.results` | `captureHomeActivityResults` | `compose_home_activity_visual` |
| `components.home.create-channel.dialog` | `captureHomeCreateChannelDialog` | `compose_home_create_channel_dialog_visual` |
| `components.members.create-agent.dialog` | `captureMembersCreateAgentCodexDialog` | `compose_create_agent_dialog_visual` |
| `components.members.create-agent.claude-dialog` | `captureMembersCreateAgentClaudeDialog` | `compose_create_agent_dialog_visual` |
| `components.members.create-agent.claude-custom-provider-dialog` | `captureMembersCreateAgentClaudeCustomProviderDialog` | `compose_create_agent_dialog_visual` |
| `components.members.agent-detail.profile` | `captureMembersAgentDetailProfile` | `compose_members_agent_detail_visual` |
| `components.channel.members.panel` | `captureChannelMembersPanel` | `compose_channel_members_panel_visual` |
| `components.channel.settings.panel` | `captureChannelSettingsPanel` | `compose_channel_settings_panel_visual` |
| `components.channel.members.add-panel` | `captureChannelAddMemberPanel` | `compose_channel_add_member_panel_visual` |
| `components.settings.page.states` | `captureSettingsPageStates` | `compose_settings_page_visual` |
| `components.settings.account.page` | `captureSettingsAccountPage` | `compose_settings_page_visual` |
| `components.settings.account.provider-linked` | `captureSettingsAccountProviderLinked` | `compose_settings_page_visual` |
| `components.settings.account.password-expanded` | `captureSettingsAccountPasswordExpanded` | `compose_settings_page_visual` |
| `components.settings.account.error-state` | `captureSettingsAccountErrorState` | `compose_settings_page_visual` |
| `components.settings.server.profile` | `captureSettingsServerProfile` | `compose_settings_page_visual` |
| `components.settings.administration.admins` | `captureSettingsAdministrationAdmins` | `compose_settings_page_visual` |
| `components.settings.administration.invites` | `captureSettingsAdministrationInvites` | `compose_settings_page_visual` |
| `components.settings.administration.join-links` | `captureSettingsAdministrationJoinLinks` | `compose_settings_page_visual` |
| `components.settings.administration.pre-join-agreement` | `captureSettingsAdministrationPreJoinAgreement` | `compose_settings_page_visual` |
| `components.settings.administration.onboarding` | `captureSettingsAdministrationOnboarding` | `compose_settings_page_visual` |
| `components.settings.administration.member-permissions` | `captureSettingsAdministrationMemberPermissions` | `compose_settings_page_visual` |
| `components.settings.administration.translation` | `captureSettingsAdministrationTranslation` | `compose_settings_page_visual` |
| `components.settings.billing.page` | `captureSettingsBillingPage` | `compose_settings_page_visual` |
| `components.settings.language-region.page` | `captureSettingsLanguageRegionPage` | `compose_settings_page_visual` |
| `components.settings.appearance.page` | `captureSettingsAppearancePage` | `compose_settings_page_visual` |
| `components.settings.notifications.page` | `captureSettingsNotificationsPage` | `compose_settings_page_visual` |
| `components.settings.connected-apps.page` | `captureSettingsConnectedAppsPage` | `compose_settings_page_visual` |

Only fall back to manual device navigation when a case has no KMP visual page,
or when the goal is explicitly to validate a full end-to-end interaction. If a
case cannot be rendered through a real provider, mark it blocked and create a
follow-up case/task instead of checking in a fake baseline.

## Package Boundary

The package is intentionally small and portable. A packed or published
`@slock/visual-testing` artifact contains:

- the CLI entry points `slock-visual` and `slock-visual-testing`
- `src/` diff, report, site, and publish implementation
- `shared/` canonical case manifest, shared tokens, and token-audit
  classifications
- this README

It does not bundle provider applications, app fixtures, checked-out React
source, Android build outputs, Harmony build outputs, generated screenshots, or
generated reports. Those stay in the calling repository.

When installed as a package, run the CLI from the Android/KMP checkout root, or
set `SLOCK_VISUAL_REPO_ROOT` explicitly:

```bash
npx -p @slock/visual-testing slock-visual validate --manifest shared
SLOCK_VISUAL_REPO_ROOT=~/AndroidStudioProjects/Slock \
  npx -p @slock/visual-testing slock-visual report --baseline react --current android
```

Path ownership stays split:

- package root: bundled `src/` and `shared/`
- repo root: `artifacts/visual-testing`, `visual-testing-results`, and
  `artifacts/visual-testing-site`
- React source root: `third_party/slock-source` by default, overridable only via
  `--react-repo-dir`, `SLOCK_REACT_REPO_DIR`, or
  `SLOCK_VISUAL_REACT_REPO_ROOT`

Before publishing or handing off a tarball, verify the package shape:

```bash
cd tools/visual-testing
npm pack --dry-run
```

The dry run must include `shared/sharedCases.json`,
`shared/sharedTokens.json`, and
`shared/tokenAuditColorClassifications.json`.

## Token Audit

`components.tokens.palette` is useful only when it is backed by a machine gate. Run:

```bash
node tools/visual-testing/src/cli.mjs token-audit
```

The audit compares React `sharedTokens.json` against KMP `SlockColor` constants by exact hex value and fails when the token source-of-truth drifts. It also writes usage reports to:

```text
artifacts/visual-testing/token-audit.json
artifacts/visual-testing/token-audit.md
```

Usage findings distinguish real-surface hard-coded colors that match known tokens from unknown colors that are outside the shared token set. Existing debt is reported instead of silently folded into the palette screenshot; cleanups should be split by component or surface.

Visual cases can be temporarily removed from default capture/diff/report/site accounting with a top-level `skip` field in `shared/sharedCases.json`:

```json
"skip": {
  "reason": "Temporarily hidden: case is not a valid parity gate yet."
}
```

Skipped cases are excluded by default. Pass `--include-skipped` for explicit one-off capture/diff work while preserving the reason in the component matrix.

## State Variants

Diff units are designed to become:

```text
caseId + variantId + provider
```

Each case may declare variants:

```json
{
  "id": "components.thread.message.row",
  "variants": [
    {
      "id": "own-right",
      "name": "Own / Right Aligned",
      "props": { "message": "own", "alignOwnMessagesRight": true }
    }
  ]
}
```

The component owner is responsible for exposing fixture props or test-only adapters that can drive real components into deterministic states. The visual-testing package reads `caseId` and `variantId`; it should not guess component parameters.

## Presentation Boundary

Providers output raw component content only. The package compositor owns review presentation: outer frame, shadow, case title, provider badge, side-by-side layout, and similarity labels. Similarity metrics are computed from raw screenshots, not the presentation frame.

## GitHub Pages

`slock-visual site` copies the latest report, provider screenshots, diff JSON, and side-by-side images into `artifacts/visual-testing-site`. It writes both `latest/` and `runs/<commit>/`, plus `index.json` and `llms.txt` for machine-readable summaries.

`slock-visual publish-gh-pages` publishes that static site into the `visual-testing/` subdirectory of the `gh-pages` branch through a temporary git worktree. Use `--repo-dir` when the Pages repository is different from the visual-testing source repository. Existing runs are preserved and the homepage is rebuilt as a commit-index.

Run `publish-gh-pages --dry-run` before pushing. The command only publishes generated report artifacts; it does not commit the app branch or turn pending/fake provider cases into a baseline.
