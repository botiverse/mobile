# Slock Agent Notes

## New Agents

- If you are a new agent joining Raft Mobile work, read
  `docs/new-agent-landing.md` before touching code.
- For Android review MP4s that demonstrate taps, drags, scrolls, or focus
  changes, enable visible touches/pointer overlay and follow
  `docs/android-review-recording.md` before attaching evidence to a task thread.

## Skills

Step-by-step procedures live in `.agents/skills/<name>/SKILL.md`. Load the
matching skill before starting that kind of work; each links to the docs it
summarizes.

- `raft-designsystem-ui` — adding or changing a page, panel or control with
  `compose/designsystem` (raft-ui source of truth, dark mode, goldens).
- `raft-ui-follow` — catching `compose/designsystem` up with a new raft-ui
  release (changelog verdicts, token bump, hand-matched values, RGB golden
  checks, small PRs).
- `raft-i18n-strings` — adding or changing user-visible text in en/zh-CN/ja.
- `raft-api-endpoint` — calling a new/changed server endpoint or DTO field.
- `raft-realtime-event` — adding a Socket.IO event or `RealtimeEvent` subtype.
- `raft-bug-triage` — stale/wrong data, missing realtime updates, scroll
  jumps, or "is the fix in this build".
- `raft-feedback-diagnostics` — reading a Hands feedback ticket and its
  diagnostics logs.
- `raft-visual-parity` — web vs Android/iOS visual cases, targeted
  `visual-testing.yml` rounds, reading diffs and residuals.
- `raft-ci-validation` — choosing hosted CI tiers, forcing iOS/full runs,
  Alpha builds, cache incidents.
- `raft-stamp-git` — push, PR, review/approve and merge through Stamp.
- `raft-release-changeset` — a version-targeted change needs its changeset;
  aggregating release notes.
- `raft-android-release` — Android release train on Hands (draft-first).
- `raft-ios-release` — iOS release build, TestFlight, iOS audit tag.
- `blacksmith-testbox` / `blacksmith-visual-testbox` — warm CI VM for focused
  Gradle work or single visual cases.
- `mobile-use`, `slock-android-adb-audit` — driving devices/emulators from
  the command line.
- `slock-android-packaging` — local signed APK artifact for `#artifacts`.
- `slock-launch-contract` — route/launch/pageData and store-boundary audits.

## Code Naming: Raft, not Slock

- The product is Raft. New types, files and functions use the `Raft` prefix;
  never introduce `Slock*` (or Kuikly-era `Kuikly*` model) names in code.
- `scripts/rename_slock_to_raft.py` performs the Slock -> Raft identifier and
  file rename. After a rebase that brings in old names, run it with `--apply`;
  `--check` exits non-zero while anything is left to rename.
- Persisted / contract strings intentionally keep their old spelling: storage
  keys, MMKV namespaces, SharedPreferences names, wire fields, log tags used by
  scripts, page/route names, `slock*` pageData keys, env vars and the
  `slockVisualFixtures` Gradle property. The manifest keeps the old activity
  component names as `<activity-alias>` entries, and the AppWidget provider
  classes (`SlockActivityWidgetProvider`, `SlockAgentStatusWidgetProvider`)
  keep their names because placed widgets persist the provider component.

## Slock server/source contract

- The TypeScript server and React source are maintained in the separate private
  repository `botiverse/slock` (default branch `staging`); Mobile does **not**
  vendor it as `third_party/slock-source` and `.gitmodules` has no entry for
  that path. Do not create that path or restore a source submodule.
- For any wire-contract / API question (HTTP routes, Socket.IO events, payload
  shapes, field names), use an external checkout of `botiverse/slock` and read
  `<slock-source>/packages/server/` (HTTP + Socket handlers) and
  `<slock-source>/packages/shared/` (shared TypeScript types). Never infer
  aliases or field names from Android call sites.
- Point tools and local checks at that checkout with `SLOCK_SOURCE_REPO`; the
  existing compatibility alias `SLOCK_REACT_REPO_DIR` is also accepted. In CI,
  the relevant workflow jobs check out `botiverse/slock` into a temporary
  `slock-source` directory; jobs that invoke the Mobile wrapper set the same
  variable. For example:

```bash
export SLOCK_SOURCE_REPO=/path/to/your/botiverse/slock
test -d "$SLOCK_SOURCE_REPO/packages/server"
test -d "$SLOCK_SOURCE_REPO/packages/shared"
```

- For review, migration, or fixture evidence, pin the external checkout to an
  exact `botiverse/slock` commit and record that SHA; do not treat a moving
  `staging` checkout as an exact contract snapshot.
  A repeatable evidence preflight is:

  ```bash
  git -C "$SLOCK_SOURCE_REPO" rev-parse --is-inside-work-tree >/dev/null
  UPSTREAM_SHA="$(git -C "$SLOCK_SOURCE_REPO" rev-parse HEAD)"
  printf 'botiverse/slock upstream SHA: %s\n' "$UPSTREAM_SHA"
  ```

  Keep that SHA beside the review, migration, or fixture result.
- `docs/` files whose names contain a date (for example,
  `*-2026-06-23.md`) are dated audit/spec snapshots. A
  `third_party/slock-source` path in one of those files records the checkout
  that existed when the snapshot was written; it is historical provenance,
  not a current checkout instruction. Current instructions belong in this
  section and must use the external checkout contract above.
- Do not re-introduce bundled web JS artifacts. There used to be an
  `artifacts/web-current/` directory with a minified bundle; it has been
  removed in favor of reading the external source checkout.

## Shared DTO API Contract Rule

- Iron rule: every Android API wire contract must go through an explicit shared
  DTO/parser in `compose/shared/src/commonMain/kotlin/build/raft/app/data/`.
  Do not add new app-side Gson/JsonObject response mappers for API payloads in
  `compose/androidApp/src/main/java/.../network/`.
- For any new or changed API endpoint, first verify the canonical field names
  in `<slock-source>/packages/server/` and `<slock-source>/packages/shared/`,
  then model that exact camelCase shape as a `@Serializable` DTO in shared. No
  snake_case aliases, no `firstObject(...)` envelope sniffing, and no
  `string("a", "b", "c")` field guessing.
- App repositories should wire responses by calling public shared parser
  functions such as `parseSharedXxx(...)` and should keep only UI composition,
  fallback context, and repository orchestration locally.
- Request bodies that represent server API contracts must likewise use named
  shared DTOs/builders. Avoid drifting ad hoc maps in app code for API shapes.
- Shared repositories should own reusable API orchestration and consume the
  shared `RaftNetworkClient` request boundary. Do not duplicate real
  repositories per platform, and do not add feature-specific platform transport
  ports such as `SlockXxxApi` when a path/query/body request through the shared
  network client is enough.
- If a shared repository interface/type can be consumed directly from Android,
  do that. Do not leave an Android "thin wrapper" whose only job is to delegate
  to shared; use typealiases or direct shared imports while the old Android
  package names are being retired.
- Platform code (Android, iOS, future targets) should adapt only the
  single bottom network layer: auth, refresh, priority, headers, and raw HTTP
  request/response. Server DTO parsing and request-body construction remain in
  shared.
- After changing `compose/shared`, validate from the compose Gradle root. The Android
  app consumes `:shared` as a direct project dependency, so no MavenLocal publish step
  is needed for normal app validation.

## Environment Web Link URL Rule

- Any Android/KMP code path that generates, normalizes, displays, copies, or
  sends a web link/base URL through an API must use the current Developer
  environment web URL, not a hardcoded production host.
- Read the active Developer environment through
  `BridgeSettingsPlatformCapabilities.loadDeveloper()` and pass its `webUrl`
  into the relevant URL normalizer, request builder, or copy/display helper.
- Keep one source of the base URL per flow. If a value is loaded and later
  copied or sent, reuse the same environment-derived base so staging, local,
  and production links cannot drift between load, API, display, and copy
  behavior.
- Example: Members invite-link loading and copying should both pass the same
  `webBaseUrl` to `normalizeInviteHumanJoinUrl(rawUrl, webBaseUrl)`.

## Route / PageData MVI Contract Rule

- Iron rule: do not pass product content/state through route, launch, or
  Kuikly `pageData` payloads. Pass only identity and intent, then let the
  destination MVI store/repository load canonical state.
- Visual, smoke, and test data must enter through fixtures/providers or direct
  typed construction, not through production route parsers.
- In-memory cache, durable local cache, realtime payloads, sparse responses,
  and legacy fallback/context are candidate snapshots, not automatically
  canonical state. Before using them in UI/MVI/cache/subscription/send scope,
  match them to the current route identity (for example server id, channel id,
  thread stream id, or parent channel id + parent message id). If they do not
  match, discard them and reload through MVI. If they match, they may only fill
  missing data and must never override canonical store state.
- This candidate-snapshot validation is a migration and contamination guard,
  not a long-term fallback payload architecture. The end state is no production
  full content/state payload or fallback display metadata in route/pageData.
- Guard this rule with structure-based tests: launch DTO descriptors,
  production parser rejection/ignore behavior, and narrow static checks on
  known route/pageData entry points. Do not use broad word-grep as a hard gate.

## Kuikly Overlay Coordinate Rule

- For Slock Kuikly Compose floating overlays, dropdowns, context menus, and
  anchored popovers, keep anchor and overlay geometry in pixels. Do not convert
  `positionInRoot()` or `coords.size` through `LocalDensity`, `toDp()`, `.dp`,
  or `toSlockDpOffset()` before passing an anchor to `RaftAnchoredPopover`.
- Use dp only for visual dimensions and spacing passed into Compose layout
  modifiers or parameters explicitly named `*Dp`.
- See `docs/kuikly-overlay-coordinate-contract.md` before changing
  `RaftAnchoredPopover`, `RaftFloatingOverlayLayout`, or trigger components
  such as `RaftAnchoredPickerTrigger`.

## Kuikly Back Handling Rule

- Any cancelable transient UI state in shared Kuikly Compose must register
  `RaftBackHandler(enabled = stateIsActive, label = "...")` while active.
  Examples include non-modal selection modes, inline search bars, full-screen
  previews, custom panels, and popovers that are not already wrapped by the
  shared overlay/dialog primitives.
- Rely on Kuikly `BackHandler` stack semantics: the most recently composed
  enabled handler owns system Back first, and disposal automatically removes it
  from the stack. Do not hand-roll per-page native back dispatch or platform
  key-event interception for ordinary cancelable UI states.
- Prefer existing primitives when possible. `RaftFloatingOverlayHost`,
  `RaftAnchoredPopover`, `RaftUnifiedDialog`, and `Modal(onWillDismiss = ...)`
  already participate in Back dismissal; page code should add a local
  `RaftBackHandler` only for transient states outside those primitives.
- When multiple transient states can overlap, keep Back priority explicit:
  modal/popover first, then nested temporary state, then page navigation.
  Add a focused policy test when that priority is not obvious from structure.

## Programmatic Scroll Ownership Rule

- Before adding or changing any app-level programmatic scroll, delayed native
  offset correction, content-size alignment, virtual-window rebase, restore,
  pager snap, or recycled scroll-view binding, read
  `docs/programmatic-scroll-ownership-contract.md`.
- App code must name the viewport intent owner and the user-override fence.
  Framework/native bridge code must additionally revalidate live scroll state
  after every asynchronous boundary and generation-fence the current
  owner/native-view binding.
- Native `isDragging == false` is not sufficient during a fling: a host may be
  in `SETTLING` while Compose still reports `isScrollInProgress`.
- Force may bypass only the active-scroll fence. It never bypasses generation,
  owner lifetime, reuse, target-validity, or geometry checks.
- Do not compensate for a framework ownership bug with a changing-index
  `scrollToItem`, or freeze canonical list/realtime state for an entire scroll.
- Reviews must apply the contract's mechanical checklist and describe test
  boundaries precisely. Helper/policy tests do not replace production wiring
  review, deterministic bridge coverage, or physical-device anchor evidence.

## Unit Test Case Rule

Origin: Raft #Perf task #17 audit (2026-07-21); full report in the #Perf
task thread. Admission checklist for new cases:

- **Assert substance.** Every test asserts a value/state of the behavior under
  test. `withTimeout` alone is not an assertion. Polling waits must fail with
  a contextual message (`error("waited for X, state=$s")`), never a bare
  `TimeoutCancellationException`.
- **Deterministic time (JVM/common unit tests).** New async coroutine unit
  tests use `runTest` + virtual time (`kotlinx-coroutines-test` is in
  commonTest). No new real `delay`/`sleep`/wall-clock polling or wall-clock
  duration assertions in JVM/common suites. Real-concurrency race tests must
  use deterministic injection points (latches, admission hooks — see
  `AttachmentDownloadCoordinatorRaceTest` for the pattern) and justify in the
  PR why virtual time cannot express the interleaving.
- **Device waits (instrumented tests).** Instrumentation/device lifecycle
  states (activity stage changes, page-spec application, task counts) have no
  virtual clock. When no deterministic callback exists, bounded monotonic
  polling is allowed: short deadline, small interval, contextual failure
  message on expiry (see `AndroidShellTaskReuseConnectedTest`'s wait
  helpers), and the PR states why no deterministic hook applies. Never a
  single unsynchronized read, never an unbounded wait, and never a wall-clock
  duration assertion as the property under test.
- **No literal clones.** A case differing from an existing one only in literal
  values is parameterized into it, not added as a new method.
- **Shared fixtures.** Do not re-declare `sampleSession`/`Fake*` helpers per
  file; extend the shared test-support fixtures instead.
- **No mirror assertions.** Do not assert entire serialized JSON bodies or
  stringified call logs; assert parsed fields/structured recorder data.
  Exception: explicit wire-contract vectors vendored with a pinned source
  SHA (see scripts/check_*_vector.sh).
- **File budget.** Test files over 1500 lines must be split before growing.
  Name files `<Subject><Facet>Test` so the subject is greppable.
- **Red naming is transient.** `*RedTest` names exist only inside a RED→GREEN
  PR sequence; rename on merge.

Retirement rules:

- Literal-duplicate cases are merged; incidental-format assertions (key
  order, toString, log wording) are rewritten or dropped; anything @Ignore'd
  for 90 days is deleted.
- An instrumented class that CI compiles but no workflow runs must be
  promoted to a workflow, documented in
  `docs/instrumented-manual-harnesses.md`, or deleted.
- Suite runtime is never a reason to delete unit cases (the JVM suite runs in
  under a minute); flaky tests are fixed or quarantined visibly, never
  silently retried.

## Kuikly Text Decoration Rule

- In shared Kuikly Compose, do not put visual container modifiers such as
  `padding`, `border`, or `background` directly on `Text`. Kuikly text nodes do
  not consume those modifiers reliably across renderers, so padding may not
  affect drawing or measurement and bordered/background text can visually press
  against its frame.
- Put those modifiers on a wrapper container instead:
  `Box(Modifier.border(...).background(...).padding(...)) { Text(...) }`.
  Keep text-only concerns such as `style`, `color`, `maxLines`, `overflow`, and
  `textAlign` on the `Text` itself.
- The same wrapper rule applies when `padding` is meant to expand a clickable
  text tap target: put `clickable` and `padding` on the wrapper, then render a
  bare `Text` inside.

## Task / Commit / Thread Discipline

Workflow rules for how every agent (human-supervised or autonomous) should move
work through Slock task threads, commits, and pushes. These rules apply on top
of the API contract and MVI rules above; they exist so other agents and humans
can verify any task in this repo from its thread alone, without reading code.

### Prefer hosted CI on Jiacheng's Mac (2026-07-13)

Agents running on Jiacheng's Mac should send Gradle, Kotlin/Native/Konan, APK,
and other resource-heavy builds/tests to hosted CI whenever the repository has
an applicable workflow. Concurrent local Gradle/Konan jobs compete for limited
CPU and memory and can slow down the owner's active work and other agents.

Keep local validation lightweight by default: source inspection,
`git diff --check`, YAML/static checks, and similarly cheap commands. Push an
exact head and use hosted workflows for compilation and test suites.
Run a heavy local build only when the owner explicitly requests it or no hosted
lane exists, and state that exception in the task thread.

For installable Android validation packages, prefer the cloud-hosted
`Android Alpha Validation` workflow. The `alpha` build type must remain
co-installable with production (`applicationId = build.raft.app.alpha`), use the
launcher label `Raft Alpha`, include the test-build badge, and publish a verified
APK artifact. Do not create temporary local Gradle edits just to add an `.alpha`
suffix or rename the app.

### Lightweight in_review handoff (2026-06-21)

When moving a task from `in_progress` to `in_review`, the task's thread should
contain a **lightweight** summary:

1. One sentence describing what changed and why.
2. Commit hash (or list of commit hashes).
3. Main integration status: explicitly say whether the work is still only on a
   worktree/branch, has an open PR, has been merged to remote `main`, or is not
   applicable. If it is not in PR, name the branch/worktree and the blocker; if
   it has a PR, include the PR number/link; if it is merged, include the remote
   merge commit.
4. (Optional) Anything owner / QA needs to runtime-verify (hilog command, emulator steps, fixture capture path).

Any meaningful progress / completion report should include this same main
integration status line. Do not leave reviewers or the owner guessing whether
the code they are hearing about is actually present on `main`.

Detailed file lists, full verification command output, and entry/walkthroughs
are still useful — put them in the commit message body, the worktree's commit
notes, or the `notes/` directory. Do not enforce a 4-piece rigid gate in the
thread; reviewers can `git show <hash>` to inspect the diff.

### No peer code gate required (2026-06-21; updated 2026-07-06)

There is no AD1 / AD2 / peer code review gate before opening a PR. When a
worktree is clean, validated locally, and the assignee judges it ready, the
assignee should push a topic branch and open a PR. If a problem surfaces later,
revert or follow up; do not block the branch-to-PR path on a review step unless
the owner explicitly asks for that review.

Trust the worktree-then-PR flow. Validation, lint, focused tests, and
runtime smoke still matter — but they are the assignee's responsibility, not a
gate another agent has to clear.

### Commit message should describe the change (2026-06-21)

Use the Angular / Conventional Commits format for the subject:
`type(scope): description` — types in use here: `fix`, `feat`, `docs`, `test`,
`refactor`, `perf`, `chore`. Visual-parity fixes use the `visual` scope
(`fix(visual): ...`) per owner ruling (2026-07-10).

Commit messages should clearly describe the change in the subject and (when
useful) the body. They are **not required** to include a task number; the
commit hash and the commit body are the trace. If a task number is helpful for
filtering, include it; otherwise omit.

### Agent Git identity comes from Stamp (2026-10-09)

Agents push, open PRs, review and merge through **Stamp**
(<https://stamp.build/llms.txt>), under their own agent identity. Do not use
the machine's shared `gh` login or another agent's SSH key for any of these:
a push made with another key is that agent's push, and a merge through the
shared `gh` account cannot be attributed to anyone.

Setup, once per agent:

```bash
raft integration login --service stamp
stamp login && stamp whoami        # principalType=agent, your own name
```

Use a checkout that pushes through Stamp:

- `stamp repo clone botiverse/mobile [DIR]`, or
- in an existing clone, add the Stamp remote **and** run `stamp repo use`:
  `git remote add stamp ssh://git@ssh.stamp.build:443/botiverse/mobile.git`
  then `stamp repo use .`. `repo use` alone only writes the commit identity and
  SSH settings; an `https://github.com/...` remote keeps pushing through the
  shared `gh` credentials and never reaches Stamp.
  The SSH setting it writes (`core.sshCommand`, Stamp key, Stamp known_hosts,
  strict host checking) applies to the whole repository, so a
  `git@github.com:...` remote in that clone stops working (`Host key
  verification failed`). Fetch and push through the Stamp remote instead
  (`git fetch stamp`), or point `origin` at Stamp.

Commit identity is whatever Stamp writes into that checkout: your name and
`<server>+<handle>@agents.stamp.build`. No `Signed-off-by:` trailer and no
`@mail.build` address are required, and nothing checks commit emails — the
author field is free text. What ties a change to an agent is Stamp's push
receipt (made with that agent's own key) and its approval / merge records. `git log`
does not identify the agent: a Stamp squash lands on `main` authored by
`stamp-build[bot]` (before Stamp it was whichever account ran the merge), and a
`Signed-off-by` line is free text. Use the Stamp PR page and receipts.
`repo use` sets identity per repository, so every worktree of that clone
shares it; in a checkout shared between agents, pass `-c user.name=...
-c user.email=...` per commit instead.

`docs/commit-signoff-policy.md` (the old `@mail.build` sign-off rule) is
superseded by this section.

### One thread, one topic

- Before replying in a thread, resolve the parent message and confirm which task
  number the thread belongs to (`slock message resolve <msgId>` if uncertain).
- If your reply actually belongs to a different task (e.g. the thread is #51
  but you're discussing #72), stop and move the discussion to the right thread.
- Do not keep expanding a wrong-thread reply just to "close it out"; an out-of-thread
  closure is worse than acknowledging and moving on.

If a thread has already mixed multiple tasks, restrict the current thread to
its parent task; other tasks must continue in their own task / spec threads.
You may ack the cross-task content ("this belongs to task X, continuing there")
without expanding it.

### DTO mocks must be verified against upstream fixtures, not invented

When writing test fixtures for typed API DTOs, **do not invent a "looks
reasonable" payload**. The Iron Rule says verify field names against the
external `botiverse/slock` checkout (`<slock-source>/`); that rule also applies
to test mocks.

A mock whose only purpose is to make a happy-path test pass is a contract drift
waiting to happen: the real server returns a different shape, and the field
that was guessed silently decodes to `null` (or default) at runtime, with no
test failure. This is exactly how the
`RaftAgentsApi.AgentActivityWireDto(val id: String)` drift (D1 of #50) was
introduced — the mock used `[{"id":"a1",...}]` instead of the upstream
`[{timestamp, entry: {kind, activity, detail}}]`.

When adding DTOs:

- If the field shape is verified, write a regression test that uses the
  canonical upstream payload (either copy-pasted from
  `<slock-source>/packages/server/...` or loaded from a JSON fixture that was
  round-trip-verified).
- If a real upstream fixture is not available, mark the DTO as "pending
  contract verification" in the test name and the source comment, and follow
  up before the task goes to In Review.
- Negative tests (`xxxRejectsGuessedWrappersAndAliases`) are still useful and
  should be retained.

### Protected `main`: PR-only integration (2026-07-06)

GitHub `main` is protected. Do not directly merge commits into local `main` as
the completion path, and do not push directly to remote `main`.

The canonical flow is (Stamp commands, run from a Stamp checkout):

1. Create a topic branch or worktree from current `origin/main`.
2. Commit with the Stamp identity of that checkout (see above).
3. Run focused validation.
4. Push the branch through Stamp and keep the receipt ID it prints, then open
   the PR from that receipt:

   ```bash
   git push stamp HEAD:refs/heads/agent/<branch>        # or `origin` in a stamp-cloned checkout
   stamp pr create --receipt <ID> --base main --title "..." --body-file PR.md
   ```

   When referring to the PR, give the Stamp page first
   (`https://stamp.build/github/botiverse/mobile/pull/<N>`); github.com does not
   show Stamp's review state or receipts.
5. Review and merge. A reviewer who is not the PR author reads the PR at its
   exact head and approves it in Stamp:

   ```bash
   stamp pr review <N> --repo botiverse/mobile
   stamp pr approve <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA>
   ```

   That approval is the GO. It is recorded by Stamp (published on the head as
   the `stamp/approval` status), not as a GitHub review, and it counts only for
   that exact head. A GO given only in Raft or a PR comment should be turned
   into a Stamp approval before merging. Never approve your own PR.

   The PR author merges through Stamp, either directly or by registering
   auto-merge, which lands the PR once that head is approved and ready:

   ```bash
   stamp pr merge <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA> --method squash
   stamp pr merge <N> --repo botiverse/mobile --head <HEAD_SHA> --base <BASE_SHA> --auto --method squash
   ```

   `--base` is the PR's recorded base SHA (`stamp pr view` prints it), not the
   tip of `main`; `main` moving ahead does not void an approval. Stamp refuses a
   PR whose head or base moved (`PR_HEAD_MOVED` / `PR_BASE_MOVED`) and names
   the current values. Do not merge with the GitHub Merge button, `gh pr merge`
   or the shared `gh` account.

   Owner/product acceptance is **not** a merge gate by default, and neither is
   any further authorisation from the person who requested the work. Require it
   only when the current task explicitly names it as a gate, or a current live
   hold assigns that decision to the owner or another accountable seat. When
   such a gate exists, do not register `--auto` until it is cleared.

   For an ordinary PR carrying no other task-declared gate and no other current
   in-scope live hold, a Stamp approval on the exact head plus a `READY`
   verdict is the whole gate set and the creator may merge. That default waives
   nothing else: any other current in-scope live hold — runtime, release,
   security, or one assigned to a different accountable seat — still applies on
   its own terms and is not displaced by this paragraph.

   A creator who prefers to wait anyway may do so, but should say plainly that
   it is their own choice and name what they are waiting for — otherwise a
   finished PR stalls with everyone assuming someone else will move it.

   `READY` is not "every test ran". mobile's CI plans jobs by changed path
   (`scripts/ci/plan_test_tier.sh`), and Stamp, like GitHub, counts a skipped
   check as satisfied unless a job in the same workflow run failed or was
   cancelled. In the PR and the task thread, name the checks that ran and the
   ones that were skipped or not run (for example, iOS compile for ordinary
   `commonMain` changes runs only in the nightly `Test` schedule).

   A new push voids the approval and any auto-merge registration. The reviewer
   reviews and approves the new head, and the author registers auto-merge
   again. For a pure rebase onto a newer base, the reviewer may approve without
   a fresh line-by-line read after checking that
   `git diff <old-base> <old-head> | git patch-id --stable` equals
   `git diff <new-base> <new-head> | git patch-id --stable` (same machine, same
   git).
6. After the PR is merged, sync local checkouts by fetching and resetting or
   fast-forwarding clean local branches as appropriate. Do not reset a dirty
   shared checkout; identify/move the dirty work first.

If local `main` is ahead of `origin/main`, treat that as reconciliation debt:
identify the ahead commits, map each to an existing PR or create a PR branch,
and ask the owner before cleaning shared state. Equivalent commits that were
squash-merged by GitHub may still appear as raw local ahead commits; use
`git log --left-only --cherry-pick main...origin/main` to distinguish real
unmerged work from squash-equivalent local commits.

Direct `git push origin main` is only acceptable if the owner explicitly
changes the protected-main policy for a specific incident. Do not use release
or helper scripts as a workaround for branch protection.

### Non-interactive Git commands

- Prefer non-interactive Git invocations in agent sessions. For commands that
  may page output, set `GIT_PAGER=cat` or `PAGER=cat` instead of letting Git
  open `less`.
- If a rebase, cherry-pick, revert, or commit continuation could open an editor,
  set `GIT_EDITOR=true` for non-interactive continuation commands. For
  interactive rebase todo editing, set `GIT_SEQUENCE_EDITOR=true` only when the
  intended todo file requires no manual edits.
- Do not let agent work block inside vim or another terminal editor. If a
  command unexpectedly enters an editor, exit without changing the file and
  rerun with explicit pager/editor environment variables.

## Shared UI Component Discipline

- Product/page code uses the raft-ui components from `compose/designsystem`
  (package `build.raft.designsystem`: `RaftButton`, `RaftIconButton`,
  `RaftTextField`, `RaftField`, `RaftSelectTrigger`, `RaftDropdownMenu`, `RaftTabs`,
  `RaftListRow`, `RaftCard`, `RaftBadge`, `RaftDialog`, `RaftBanner`, ...) and
  its tokens/typography for every common control. Do not hand-roll back
  buttons, icon buttons, primary/secondary buttons, text fields, search
  controls, menu rows or tabs with ad hoc `Box`/`Row`/`clickable` styling.
- The source of truth is raft-ui (raft-design / the web). When a page needs a
  control or variant `compose/designsystem` does not have yet, first add it to
  `compose/designsystem`, aligned with the raft-ui recipe (sizes, radii,
  strokes, states, type), with screenshot tests, then use it. Do not fix the
  same visual primitive separately inside each page; page-local UI is only for
  one-off layout composition around design-system controls.
- The older `RaftBrutal*` components (`RaftBrutalActionButton`,
  `RaftBrutalIconButton`, `RaftBrutalTextField`, `RaftBrutalSearchField`, ...)
  and the helpers under `compose/shared/src/commonMain/kotlin/build/raft/app/components/`
  are pending migration: add no new usages, and replace them with the
  design-system component when you touch that UI.
- Local wrappers are acceptable only as semantic adapters and keep the
  design-system defaults (stroke, radius, padding, type, disabled and focus
  states, icon treatment) unless the raft-ui spec defines a variant. A repeated
  variant belongs in `compose/designsystem`, not in per-page styling.
- Metrics come from the design system, not from page code: control heights,
  icon-button sizes (44dp touch target), menu-row heights, radii and stroke
  widths use `compose/designsystem` tokens. raft-ui controls cast no shadow;
  only stickers keep the hard offset.

## Kuikly Status

- The mobile UI runs on JetBrains Compose Multiplatform; Kuikly UI was removed
  (see `compose/designsystem` for the design-system module). The vendored
  Kuikly AI skills, rules and the KuiklyUI reference tree were removed too; do
  not re-add them. New UI is Compose Multiplatform.
- `.agents/skills/slock-*` contains Slock-specific workflow guidance
  (currently `slock-android-adb-audit`, `slock-android-packaging`,
  `slock-launch-contract`).

## KuiklyUI Release (retired)

- Since the Compose Multiplatform switch neither the Gradle build nor the iOS
  Podfile consumes Kuikly UI (`com.tencent.kuikly-open` / OpenKuiklyIOSRender).
  The immutable release closure, manifest gate and artifact fetcher were
  removed; `scripts/ci/test_kuikly_raft_consumer_contract.py` now fails if a
  Gradle build file or workflow consumes Kuikly UI again. KuiklyBase libraries that are not Kuikly UI
  (`resource-core`) stay. NetworkKMM was removed with the Ktor 3 switch (2026-10-23).

## HarmonyOS

- This repository targets Android and iOS only; it has no HarmonyOS (OHOS)
  app, target, build, CI lane, device tooling, or release step. Do not add
  them. HarmonyOS is planned as a separate ArkTS rewrite outside this repo
  (see `README.md`). The legacy Kuikly OHOS app (last release 1.14.x) lives
  only on the `release/1.14.*` branches.

## Local Preferences

- Local product preferences must be represented as shared repositories over
  `SharedPreferenceStore` in `compose/shared`. Business code must not create
  platform `SharedPreferences` instances, choose file names, or hand-roll
  preference keys in feature pages.
- In shared code, `KuiklySharedPreferencesKeyValueStore` namespaces are only
  key prefixes such as `tasks:viewMode`; do not reinterpret those namespaces as
  platform preference file names.
- Platform hosts only adapt the bottom store: Android provides
  `AndroidSharedPreferencesKeyValueStore` using the shared
  `RaftLocalPreferenceStore.AndroidPreferencesName`; Kuikly hosts provide
  `KuiklySharedPreferencesKeyValueStore` through `kuiklySharedPreferenceStore(...)`.
  Business repositories for locale, chat layout, notifications, tasks view mode,
  and future local settings stay in shared.

## Compose Shared Workflow

- The Android app lives at `compose/androidApp` and consumes Kuikly Compose shared code
  through `implementation(project(":shared"))` in the same compose Gradle project.
- When changing code under package `build.raft.app.*`, make the source change in
  `compose/shared/src/commonMain/kotlin/...`, not in `compose/androidApp/src/main/java/...`.
- After changing `compose/shared`, compile the shared module and Android app from the
  compose Gradle root:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin
```

- Then package the Android app from the same Gradle root, for example:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:assembleDebug
```

- Do not keep shadow copies of shared classes in
  `compose/androidApp/src/main/java/build/raft/app/...`.
  Temporary app-side shims can make one APK build, but they drift from the real shared
  artifact and should be removed before handoff.

## Gradle Build Safety

- If Gradle compilation fails due to **cache corruption** or because the Gradle daemon
  is **locked/occupied by another process**, **stop immediately** and report the failure.
  Do **not** retry the build — retrying will not resolve cache or lock contention and
  wastes time.
- Before reporting, capture the exact error message and the Gradle task that failed.
  Let the owner or the blocking process holder decide the next step.

## Network Priority Workflow

- Every Android request that reaches `build.raft.app.network.SlockApiClient`
  must run inside `withRaftRequestPriority(...)`; missing priority is a bug.
- New product API routes live in `compose/shared` behind shared
  `@Serializable` request/response DTOs and `RaftNetworkClient`. Do not add
  parallel Android endpoint facades, app-local JSON parsers, `mapOf` request
  bodies, ad hoc `JsonObject`, or route-local anonymous body shapes.
- Shared `RaftNetworkClient` APIs must require explicit `DataRequestPriority`
  with no no-priority overloads/defaults, so new API/fake call sites fail to
  compile until they choose `Foreground`, `Visible`, or `Background`.
- Priority choice: `Foreground` is only for user-blocking opens/mutations;
  `Visible` is for on-screen refresh/pagination/retry; `Background` is for
  warmup, reconcile, polling, cached silent refresh, diagnostics, and preloads.
- HTTP lanes stay separated per priority: on Android each priority has its own
  OkHttp Dispatcher (limits = `RAFT_DEFAULT_MAX_*_REQUESTS` in
  `RequestPriority.kt`), on iOS its own NSURLSession. Do not collapse them into
  one client/dispatcher; background waits behind active or queued
  Foreground/Visible work.

## Shared MVI + Repository/DataSource Layered Contract

The project enforces a strict unidirectional data-flow chain for all feature pages:

```
UI -> Store -> UseCase/Interactor -> Repository -> DataSource/API/HostCapability
```

Core constraints every agent must follow:

- **UI layer**: only reads `State`, only emits `Intent`, only receives one-shot
  `Effect`. Never calls Repository/DataSource directly.
- **Store layer**: owns the reducer, concurrency/debounce/stale-token, in-flight
  idempotency guard, no-op equality gate, and typed result/error mapping.
  Same snapshot must not re-emit; local patches must not cause full-page refresh.
- **Effect boundary**: hot `LaunchedEffect` / `snapshotFlow` work must be keyed by
  stable render-axis signatures, not whole payload/list objects or mutable flags
  that the effect itself writes. Every `snapshotFlow` used for scrolling, paging,
  visibility, or read markers must apply `distinctUntilChanged` (or an equivalent
  equality gate), and every boolean/state assignment inside the collector must
  be guarded by `if (old != new)`.
- **IO intent idempotency**: any intent that reaches Repository/DataSource/host IO
  from a hot UI path must be single-flight and marker-based in the Store. Repeated
  visibility/read/paging/refresh intents for the same route/entity marker must
  no-op or coalesce to the latest marker instead of issuing parallel requests.
- **Selector boundary**: UI must not subscribe to an entire shared store state when
  it only needs one route/channel/thread/member slice. Expose stable selectors or
  slice flows keyed by the exact scope the UI consumes, and put `distinctUntilChanged`
  / equality no-op gates on selector output. Realtime/cache updates may update the
  store globally, but unrelated slices must not cause the current page/composer to
  recompute or receive another candidate/status emission.
- **Repository layer**: exposes typed domain results (`DataResult<T>` / `DomainError`).
  Never leaks raw HTTP response, token, headers, or platform-specific payloads upward.
- **Success path**: patch only the affected entity slice. Do not replace the whole
  state tree.
- **Failure path**: keep the previous state intact and attach a route/action error.
  Never clear content on failure.
- **Cache / realtime**: must flow back through the Store reducer. Never bypass MVI
  to directly mutate UI state from a socket event or local cache read.
- **Shared MVI runtime**: `build.raft.app.mvi` provides the lightweight foundation
  (`Intent`, `State`, `Effect`, `Store`, `dispatch`, `no-op gate`, `single-flight`,
  `stale token`, `transition log`, `test harness`). Feature stores (Home, Thread,
  Settings, Tasks) extend this contract, but keep their own state shape, selectors,
  and snapshot granularity.

## Compose ViewModel Lifecycle Rule

This rule prevents a real production crash (white screen on launch).

- **Never** access `viewModelScope`, `pagerId`, `Utils.bridgeModule(pagerId)`, or
  any Pager/runtime-context-dependent object inside a ViewModel constructor,
  property initializer, or `init {}` block.
- Kuikly may not have finished Pager initialization when the ViewModel is
  constructed. Premature access triggers `lateinit property pagerId has not been
  initialized` and blocks the first frame.
- **Correct pattern**: save pure dependencies (repository, auth store, data source,
  configuration) in the constructor. Defer creation of `Store` / `Effect` / coroutine
  jobs that need `viewModelScope` to a `lazy { ... }` block, or to an explicit
  `start` / `attach` / `dispatch(Start)` call after composition begins.
- In `onCleared()`, check `isInitialized()` before touching lazy stores to avoid
  triggering initialization during cleanup.

Example:

```kotlin
class SharedExampleViewModel(
    private val dataSource: SharedExampleDataSource
) : ViewModel() {
    private val storeDelegate = lazy {
        SharedExampleStore(dataSource = dataSource, scope = viewModelScope)
    }
    private val store: SharedExampleStore by storeDelegate

    val state: StateFlow<SharedExampleState>
        get() = store.state

    fun dispatch(intent: SharedExampleIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) store.clear()
        super.onCleared()
    }
}
```

## Kuikly Compose API Quick Reference

Do not duplicate the Compose API mapping here. The canonical source is
`.agents/rules/kuiklyComposeDSL.mdc`; read it before touching Kuikly Compose
code, especially for package imports, unsupported APIs/modifiers, TextField,
nested scroll, pull-to-refresh, animation, and lazy list/grid differences.

## Multi-Platform Build Validation

After any shared-layer change, validate all target platforms before handoff:

```bash
# 1. Shared Android compile
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid

# 2. Shared iOS compile (Kotlin/Native cross-check; macOS host)
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileKotlinIosSimulatorArm64

# 3. Android app compile + assemble
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:compileDebugKotlin :androidApp:assembleDebug

# 4. Focused unit tests
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:testDebugUnitTest \
  --tests "build.raft.app.core.data.quality.NetworkPriorityCallSiteTest*" \
  --tests "build.raft.app.core.data.mock.MockRepositoryTest*"
```

Key invariants:
- If step 1 fails, fix shared before validating the app. The Android app now sees
  shared changes directly through the project dependency.
- Step 2 must pass even if you only changed commonMain; Kotlin/Native catches
  expect/actual mismatches, missing native source sets, and
  platform-incompatible dependencies. It is also what catches JVM-only APIs in
  commonMain (e.g. `Map.putIfAbsent`/`Map.merge`) that compile fine for
  Android but break the iOS build — never push a commonMain change
  that has only been validated by step 1.

## Visual Testing (Cross-Provider Parity)

React web is the visual baseline; Android/iOS captures are compared
against it (and against each other) by
`.github/workflows/visual-testing.yml`. The data model, CLI, and report site
live in the React repo under `packages/visual-testing/` — read its README and
`docs/provider-pair-first.md` there before changing capture or report
behavior. `scripts/slock-visual-testing.mjs` here is a compatibility shim
around that CLI.

Pipeline contracts:

- Comparisons are provider pairs (`--pairs react__android,react__ios,android__ios`
  when iOS capture runs; `react__android` otherwise). Pair keys normalize by
  provider precedence react > android > ios.
- The report job publishes images first with analysis seeded from the previous
  gh-pages state; AI analysis runs as a separate job with a cancel-in-progress
  concurrency group and a generation guard (it republishes only if the
  gh-pages latest run is still its own run). Do not re-couple analysis into
  the report job, and do not remove the generation guard.
- Renderer capability facts travel as a single-line
  `SLOCK_RICHTEXT_CAPS {json}` marker printed after first frame; CI collects
  it into per-case metadata via `scripts/collect_richtext_caps.py`. On
  Android it surfaces through logcat (`System.out:I` must be in the captured
  tags). A missing marker or key means unknown — never default a capability
  to true.
- Known intentional platform differences are declared in the case manifest
  (`declaredDivergences` objects); the site shows them as attribution badges
  and they are never subtracted from Different counts. Fix the render or
  declare the divergence — do not tune tolerances to make a case pass.

## Hands Release & Feedback Diagnostics

Raft mobile ships and reports through Hands. Two reference docs:

- `docs/android-hands-release-practices-2026-07-02.md` — release lane:
  versioning rule (`versionCode` must strictly increase), draft-first flow,
  and the release-notes (changelog) format.
- `docs/quiver-feedback-diagnostics-zip.md` — how to open and read a Quiver
  feedback diagnostics attachment (`slock-feedback-*.zip`: summary +
  `app_logs/*.jsonl`), the app-owned log format, and triage tips. Quiver
  delivers the raw attachment; this app owns the archive layout.

## Versioned Mobile Changesets

Raft Mobile release notes use version-scoped feature changesets under
`change-logs/<version>/changesets/`. When a task, owner decision, or acceptance
contract explicitly targets a release such as `1.6.0`, the implementation is
not complete without its own changeset in that version directory.

The release lane aggregates those changesets separately for each shipped
platform under `change-logs/<version>/<platform>/{zh-CN,en}.md`, where
`<platform>` is `android` or `ios` (historical versions up to 1.14.x may also
carry a legacy `ohos` directory). A platform aggregate contains only
behavior shipped on that platform and does not repeat the platform name in
each bullet. Historical root-level `change-logs/<version>/{zh-CN,en}.md` files
remain supported as workflow fallbacks, but new versions must use the
platform directories.

- Add one kebab-case Markdown file per user-facing feature, improvement, or
  fix. Use
  `change-logs/1.6.0/changesets/app-shell-idle-prewarm.md` as the
  current structure reference.
- Ship the changeset in the same PR as the product change whenever possible.
  If the target version is assigned only after the product PR merged, create a
  follow-up changeset PR immediately and reference the original product PR.
- Preserve the frontmatter contract: `type`, `area`, `platforms`,
  `pull_request`, and `rollout`. The `pull_request` value must identify the
  product PR and must not remain a placeholder when the changeset merges.
- Include both `## zh-CN` and `## en`. Write user-visible behavior and impact,
  not implementation details, test inventories, commit hashes, or internal
  architecture narration.
- Do not guess a target version. If no version was explicitly assigned, do
  not place the change in a version directory merely because that is the next
  planned release; wait for owner/release-lane classification.
- Do not edit consolidated
  `change-logs/<version>/<platform>/{zh-CN,en}.md` as the per-feature delivery
  path. The release lane owns platform aggregation and review using the
  accepted version changesets.
- Before moving a version-targeted task to In Review, verify the changeset is
  present, the bilingual copy matches the shipped scope, and the product PR
  number/rollout metadata are accurate.

## Adding a RealtimeEvent subtype — checklist

Missing any of these compiles-or-bites in a different layer than the one you
edited (it bit #360 and #387 on the same day):

1. Event name into `RealtimeSocketEvents.ServerWireEvents` — the socket client
   subscribes ONLY to that list; an unlisted event is silently never received.
2. `SettingsHomePage.kt`: both exhaustive `when`s —
   `performanceMarkerName()` and `performanceMarkerPhase()`.
3. `SocketIoRealtimeClient.kt` (androidApp): both exhaustive `when`s —
   `realtimeDiagnosticName()` and `realtimeDiagnosticFields()`. The shared
   compile does NOT check these; only the androidApp compile does.
4. If the event should refresh Home while hidden: `updatesHomeSnapshot()` /
   `homeRealtimeDirtyReason()` (both have `else` branches — decide, don't
   default silently).

## Peer Review Convention (互审公约)

Approved by @artin 2026-07-18. Rationale: review rounds ballooned to 30+ on a
PR whose product code had passed source review early — the remaining rounds
were spent proving the test harness itself. Keep the rigor, but gate by risk
and cap the rounds.

1. **Risk-tiered gates.** Small, local, test-covered fixes merge on
   source-review PASS + green CI. Heavy gates (runtime evidence,
   connected-test teeth, exact-SHA binding) are reserved for
   architecture/contract/cross-platform changes.
2. **Round cap = 3.** If a review has not converged within 3 rounds, escalate
   to @artin, or merge the source-PASSED portion and open a follow-up task
   for the remainder. Do not spin exact SHAs indefinitely.
3. **Harness issues are separate tasks.** Defects in test fixtures or CI
   itself belong to the harness domain (@XiShi / stamp) and get their own
   task; they must not block a product PR. The product PR's test bar is:
   does not break existing tests + passes source review.
4. **HOLD requires a behavior.** A HOLD verdict must name the concrete
   behavior that breaks if merged. Anything else is a non-blocking comment.
5. **Slim verdict template.** Threads carry only: verdict + at most 3
   blockers + an evidence link. Long analysis, raw artifacts, and SHA
   citations go in PR comments, not chat threads.
