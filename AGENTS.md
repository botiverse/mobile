# Slock Agent Notes

## New Agents

- If you are a new agent joining Raft Mobile work, read
  `docs/new-agent-landing.md` before touching code.
- For Android review MP4s that demonstrate taps, drags, scrolls, or focus
  changes, enable visible touches/pointer overlay and follow
  `docs/android-review-recording.md` before attaching evidence to a task thread.

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
- Before sending a docs/code change to review, run the explicit source-path
  checker. New files are governed by default; only the currently audited
  historical snapshots are allowlisted, so a date in a filename is not an
  exemption:

  ```bash
  scripts/check_slock_source_path_contract.sh
  ```

  The command must exit successfully. To preserve an additional dated
  snapshot, add it to the script's explicit historical list in the same
  change; do not broaden the checker with a filename pattern.
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
  shared `SlockNetworkClient` request boundary. Do not duplicate real
  repositories per platform, and do not add feature-specific platform transport
  ports such as `SlockXxxApi` when a path/query/body request through the shared
  network client is enough.
- If a shared repository interface/type can be consumed directly from Android,
  do that. Do not leave an Android "thin wrapper" whose only job is to delegate
  to shared; use typealiases or direct shared imports while the old Android
  package names are being retired.
- Platform code (Android, OHOS/Kuikly, future targets) should adapt only the
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
  or `toSlockDpOffset()` before passing an anchor to `SlockAnchoredPopover`.
- Use dp only for visual dimensions and spacing passed into Compose layout
  modifiers or parameters explicitly named `*Dp`.
- See `docs/kuikly-overlay-coordinate-contract.md` before changing
  `SlockAnchoredPopover`, `SlockFloatingOverlayLayout`, or trigger components
  such as `SlockAnchoredToolbarChip`.

## Kuikly Back Handling Rule

- Any cancelable transient UI state in shared Kuikly Compose must register
  `SlockBackHandler(enabled = stateIsActive, label = "...")` while active.
  Examples include non-modal selection modes, inline search bars, full-screen
  previews, custom panels, and popovers that are not already wrapped by the
  shared overlay/dialog primitives.
- Rely on Kuikly `BackHandler` stack semantics: the most recently composed
  enabled handler owns system Back first, and disposal automatically removes it
  from the stack. Do not hand-roll per-page native back dispatch or platform
  key-event interception for ordinary cancelable UI states.
- Prefer existing primitives when possible. `SlockFloatingOverlayHost`,
  `SlockAnchoredPopover`, `SlockUnifiedDialog`, and `Modal(onWillDismiss = ...)`
  already participate in Back dismissal; page code should add a local
  `SlockBackHandler` only for transient states outside those primitives.
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

Commit identity is whatever Stamp writes into that checkout: your name and
`<server>+<handle>@agents.stamp.build`. No `Signed-off-by:` trailer and no
`@mail.build` address are required, and nothing checks commit emails — the
author field is free text. What ties a change to an agent is Stamp's push
receipt (made with that agent's own key) and its approval / merge records.
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
`SlockAgentsApi.AgentActivityWireDto(val id: String)` drift (D1 of #50) was
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

- Product/page code must prefer the existing shared brutal components for
  common controls: `SlockBrutalActionButton`, `SlockBrutalIconButton`,
  `SlockBrutalTextField`, `SlockBrutalSearchField`, section labels, chips, and
  modal/action helpers. Do not hand-roll local back buttons, icon buttons,
  primary/secondary buttons, text fields, or search controls with ad hoc
  `Box`/`Row`/`clickable` styling when a shared component already covers the
  behavior.
- Local wrappers are acceptable only as semantic adapters, and should preserve
  shared component defaults for border, hard shadow, padding, typography,
  disabled behavior, and icon treatment unless the product spec explicitly
  calls out a variant. If a repeated variant is needed, promote it to a shared
  component instead of copying per-page styling.
- Iron rule: when React parity or Product feedback identifies a reusable
  control pattern (select/dropdown, textarea counter, clear action, refresh
  affordance, section label accessory, etc.), first add or extend a shared
  component under `compose/shared/src/commonMain/kotlin/build/raft/app/components/`
  and then wire pages to that component. Do not fix the same visual primitive
  separately inside each page; page-local UI is only allowed for one-off layout
  composition around shared controls.
- Members/detail pages in particular should use the shared brutal buttons for
  top-left back navigation and all action buttons. Do not reintroduce bespoke
  members-only button metrics such as custom 30dp icon buttons, 1dp shadows, or
  caption-sized action text for ordinary page actions.

## Kuikly References And Skills

- Workspace-level Kuikly guidance lives under `.agents/`:
  - `.agents/skills/kuikly-*` contains the vendored skills from
    `Tencent-TDS/KuiklyUI-AI`.
  - `.agents/skills/slock-*` contains Slock-specific workflow guidance
    (currently `slock-android-adb-audit`, `slock-android-packaging`,
    `slock-launch-contract`).
  - `.agents/rules/kuiklyComposeDSL.mdc` and `.agents/rules/kuiklyDSL.mdc` contain
    the Kuikly Compose / Kuikly DSL rules.
  - `.agents/kuikly-ui-ai-source.txt` records the upstream source and commit.
- For Kuikly, OHOS, shared shell, navigation, assets, native bridge, or
  recomposition work, read the relevant workspace skill/rule first instead of
  relying on Android-native assumptions.
- The local Tencent KuiklyUI upstream reference lives at
  `.agents/skills/kuikly-ui-framework/references/KuiklyUI/`. Keep KuiklyUI
  reference/demo/source lookup there instead of adding another copy under
  `third_party/`.
- Useful KuiklyUI reference paths:
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/compose/`
    for Compose DSL demos such as popups, gestures, Canvas, dialogs, lazy lists,
    and text/input behavior.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/ohosApp/` for the
    HarmonyOS host shell.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/androidApp/` for the
    Android host shell.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/core-render-ohos/`
    and `.agents/skills/kuikly-ui-framework/references/KuiklyUI/compose/` for
    render/runtime implementation details.
- Treat the KuiklyUI reference tree as vendor/reference source. Do not modify it
  unless explicitly asked; for Slock product fixes, change Slock shared/app code.
- Cross-platform visual states must not rely on parent/view opacity when the
  intended result is a specific token color. Kuikly Compose emits
  `Modifier.alpha(...)` as an `opacity` prop; Android applies that through
  `View.alpha`, while OHOS applies it through ArkUI `NODE_OPACITY`. The final
  subtree blending can differ, especially for disabled buttons/icons on light
  backgrounds. Prefer explicit ARGB colors such as
  `AccentPink.copy(alpha = 0.3f)` and matching icon colors (`#4D000000`) for
  disabled/pressed states instead of wrapping the whole component in
  `Modifier.alpha(0.3f)`.
- To refresh the `.agents/` Kuikly AI skill/rule bundle from upstream, run:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
scripts/sync_kuikly_ai_skills.sh
```

## KuiklyUI Release

- Mobile consumes the immutable Raft release pinned in
  `compose/gradle/kuikly-release-closure.json`; it does not carry a Kuikly
  source gitlink or build Kuikly into Maven Local.
- `scripts/ci/verify_kuikly_release_manifest.sh` is the fail-closed version
  admission gate. Android/KMP/OHOS then resolve exact coordinates from the
  exclusive Raft repository, while iOS and the OHOS renderer are fetched from
  the same manifest with byte verification. Set `KUIKLY_FULL_READBACK=1` only
  for an explicit audit/recovery run that must re-read the full release set.
- Fork development and publication happen in the KuiklyUI repository. To
  upgrade Mobile, publish a complete immutable successor first, then update
  the closure, exact Gradle versions, CocoaPods lock, and OHOS lock together.
- The `.agents/skills/kuikly-ui-framework/references/KuiklyUI` tree remains a
  vendor reference for API and implementation research; it is not a build
  input or consumer fallback.

## OHOS Kuikly Workflow

- For local HarmonyOS virtual-device lifecycle, HDC connection, signed-HAP
  install, screenshots/layout dumps, and repeatable input smoke, read
  `docs/ohos-emulator-setup.md` before starting an emulator.

- The Slock OHOS app bundle/module/ability is `build.raft.mobile` / `entry` /
  `EntryAbility`.
- `hdc` may not be on `PATH`; the default DevEco path in this workspace is
  `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc`.
- Do not assume global `hvigor` or `hvigorw` commands exist on this machine.
  Command-line OHOS builds should use DevEco Studio's bundled Node +
  `hvigorw.js` and bundled `ohpm`.
- The default DevEco installation path used by this project is
  `/Applications/DevEco-Studio.app/Contents`. Override it in scripts with
  `DEVECO_STUDIO_HOME` only when DevEco is installed elsewhere.
- To sync and assemble a HAP without relying on global `hvigorw`, run:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose/ohosApp
/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm install --all
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw.js \
  --sync -p product=default --analyze=normal --parallel
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw.js \
  --mode module -p module=entry@default -p product=default \
  -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

- To build, install, and launch the OHOS app, run the wrapper from the Compose
  project. It applies the same bundled DevEco toolchain path above, then installs
  the signed HAP on a connected target. The script is not guaranteed to have its
  executable bit set, so prefer invoking it through `sh`:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
sh ohosApp/runOhosApp.sh
```

- `scripts/capture_ohos_screenshot.sh` uses `snapshot_display`; on some current
  OHOS emulator images, `snapshot_display` rejects `.png` output names and only
  accepts `.jpeg`. If screenshot capture fails with a suffix error, use a remote
  `.jpeg` path and pull that file with `hdc file recv`.

## OHOS Kuikly SharedPreferences

- Kuikly OHOS has two SharedPreferences implementations:
  `KRSharedPreferencesModule` uses XML files and is compatible with all API
  versions; `KROhSharedPreferencesModule` uses the native OpenHarmony
  `oh_preferences` C API / GSKV format and requires OpenHarmony API >= 13.
- Prefer `KROhSharedPreferencesModule` on API 13+ by enabling it through the
  Kuikly entry config, for example `Kuikly({ useOhSharedPreferences: true, ... })`.
- When business code builds a platform Preference instance on OHOS, never set
  `fileName` to `KROhSharedPreferencesModule`; that name is reserved by the
  module implementation and will conflict with its cache path.
- In shared code, `KuiklySharedPreferencesKeyValueStore` namespaces are only
  key prefixes such as `tasks:viewMode`; do not reinterpret those namespaces as
  platform Preference file names.
- Local product preferences must be represented as shared repositories over
  `SharedPreferenceStore` in `compose/shared`. Business code must not create
  platform `SharedPreferences` / OHOS `Preference` instances, choose file names,
  or hand-roll preference keys in feature pages.
- Platform hosts only adapt the bottom store: Android provides
  `AndroidSharedPreferencesKeyValueStore` using the shared
  `SlockLocalPreferenceStore.AndroidPreferencesName`; Kuikly/OHOS provides
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
  must run inside `withSlockRequestPriority(...)`; missing priority is a bug.
- New product API routes live in `compose/shared` behind shared
  `@Serializable` request/response DTOs and `SlockNetworkClient`. Do not add
  parallel Android endpoint facades, app-local JSON parsers, `mapOf` request
  bodies, ad hoc `JsonObject`, or route-local anonymous body shapes.
- Shared `SlockNetworkClient` APIs must require explicit `DataRequestPriority`
  with no no-priority overloads/defaults, so new API/fake call sites fail to
  compile until they choose `Foreground`, `Visible`, or `Background`.
- Priority choice: `Foreground` is only for user-blocking opens/mutations;
  `Visible` is for on-screen refresh/pagination/retry; `Background` is for
  warmup, reconcile, polling, cached silent refresh, diagnostics, and preloads.
- OkHttp lanes stay separated per priority: Foreground `12`, Visible `8`,
  Background `4`. Do not collapse them into one client/dispatcher; background
  waits behind active or queued Foreground/Visible work.

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

# 2. Shared OHOS compile (Kotlin/Native cross-check)
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew -c settings.ohos.gradle.kts \
  :shared:compileKotlinOhosArm64 \
  :ohosBridge:compileKotlinOhosArm64

# 3. Android app compile + assemble
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:compileDebugKotlin :androidApp:assembleDebug

# 4. OHOS HAP (requires DevEco Studio; uses bundled hvigorw.js, not a global CLI)
cd /Users/artin/AndroidStudioProjects/Slock/compose
sh ohosApp/runOhosApp.sh

# 5. Focused unit tests
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:testDebugUnitTest \
  --tests "build.raft.app.core.data.quality.NetworkPriorityCallSiteTest*" \
  --tests "build.raft.app.core.data.mock.MockRepositoryTest*"
```

Key invariants:
- If step 1 fails, fix shared before validating the app. The Android app now sees
  shared changes directly through the project dependency.
- Step 2 must pass even if you only changed commonMain; Kotlin/Native catches
  expect/actual mismatches, missing `ohosArm64` source sets, and
  platform-incompatible dependencies. It is also what catches JVM-only APIs in
  commonMain (e.g. `Map.putIfAbsent`/`Map.merge`) that compile fine for
  Android but break the iOS and OHOS builds — never push a commonMain change
  that has only been validated by step 1. Do not count on CI to catch this
  after push: the CI-side OHOS shared compile is a non-blocking lane (red
  does not stop a merge; failures are fixed forward), so this local step is
  the real gate until the pre-merge JVM-only-Map grep gate lands on main.
- The `ohosBridge` module is the intentionally narrow C ABI for
  `libshared.so`. Keep its public API primitive and minimal; realtime calls
  cross the existing KNOI bridge. Building the binary directly from `:shared`
  exports the full shared API and creates an impractically large C adapter.
- Step 4 may emit warnings from `@kuikly-open/render` `.d.ets` strict-typing
  comments; treat them as warnings unless Hvigor reports a hard compile error.

## Visual Testing (Cross-Provider Parity)

React web is the visual baseline; Android/iOS/OHOS captures are compared
against it (and against each other) by
`.github/workflows/visual-testing.yml`. The data model, CLI, and report site
live in the React repo under `packages/visual-testing/` — read its README and
`docs/provider-pair-first.md` there before changing capture or report
behavior. `scripts/slock-visual-testing.mjs` here is a compatibility shim
around that CLI.

Pipeline contracts:

- Comparisons are provider pairs (`--pairs react__android,react__ios,android__ios`
  when iOS capture runs; `react__android` otherwise). Pair keys normalize by
  provider precedence react > android > ios > ohos.
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
`<platform>` is `android`, `ios`, or `ohos`. A platform aggregate contains only
behavior shipped on that platform and does not repeat the platform name in
each bullet. Historical root-level `change-logs/<version>/{zh-CN,en}.md` files
remain supported as workflow fallbacks, but new versions must use the
platform directories.

- Add one kebab-case Markdown file per user-facing feature, improvement, or
  fix. Use
  `change-logs/1.6.0/changesets/sync-core-v2-sole-apply-canary.md` as the
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
