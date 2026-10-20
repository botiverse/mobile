# CI Gradle cache: epoch rotation & quarantine runbook

One page. Origin: the 2026-07-23 shared-cache poisoning incident (truncated AGP jar + broken KSP
loader inside one Blacksmith `gradle-dependencies-*` entry deterministically failed every Android
compile; attribution in #proj-chat task #781, design review in task #786).

## What exists

- `.github/gradle-cache-epoch` — exactly one logical line, `[A-Za-z0-9_.-]{1,32}` (final newline
  optional, CR rejected; the Bash reader and the Python checker share one byte contract).
  Exported to every `setup-gradle` step as `GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=<epoch>-`.
  Rotation semantics — note that gradle/actions runs `cache-read-only: true` off the default
  branch, so saves only happen on `main`:
  - a PR (including the rotation PR itself) can only demonstrate the parser/env wiring and the
    top-level MISS under the new family — never the save or the HIT;
  - after merge, the FIRST eligible main run misses and saves the `<epoch>-` family; the NEXT
    main run proves the HIT. Both receipts belong in the rotation PR's thread.
  NOTE (verified against gradle/actions@v5 source): extracted-entry keys
  (`gradle-dependencies-*`, `gradle-transforms-*`, ...) are NOT covered by the prefix.
- `.github/gradle-cache-quarantine` — incident-response file, EMPTY in steady state. Allowlisted
  bundle names (`dependencies`, `transforms`), one per line (final newline optional — a trailing
  active entry is honored; CR rejected). Listed bundles are exported as
  `GRADLE_BUILD_ACTION_SKIP_RESTORE=<b1,b2>`: their poisoned entries stop being restored, at the
  cost of re-resolving that bundle every run in every wired job. A quarantine line must stay until
  the poisoned bundle key rotates naturally (its hashed file-set changes); this is a quarantine,
  not a purge — the backend entry remains until eviction. Evidence status: the suppression path
  is verified from gradle/actions@v5 source plus the env injection receipts; an actually observed
  `Not restoring extracted cache entry` line still needs a main run with an active quarantine —
  when the first real incident uses this, verify that line in the run log and attach it to the
  incident PR.
- `.github/actions/gradle-cache-policy` — the fail-closed reader every workflow job runs
  immediately before `gradle/actions/setup-gradle`. Malformed policy fails the job.
- `scripts/ci/check_gradle_cache_policy_wiring.py` — CI invariant (workflow
  `gradle-cache-policy-lint.yml`): every `setup-gradle` step must be preceded by the policy step,
  and both policy files must be valid. Prevents wiring drift when workflows are added.

## When CI fails on a corrupted shared cache entry

Signatures like: `Failed to transform ... instrumentation-dependencies.bin`, `EOFException:
read N bytes; M expected` on a dependency jar, `ClassNotFoundException` for classes that exist,
reproducing byte-identically across runs/PRs at the same restore key.

1. Top-level Gradle home entry poisoned → **epoch rotation request**: one PR bumping
   `.github/gradle-cache-epoch` (e.g. `1` → `2`).
2. Extracted bundle poisoned (key visible in the `Restored cache entry with key
   gradle-<bundle>-v1-...` log line) → **quarantine**: one PR adding that bundle name to
   `.github/gradle-cache-quarantine`, with a dated comment naming the poisoned key. Remove only
   after that key rotates.
3. Unsure which layer → do both in one PR; quarantine is reversible.

Dual control is procedural, not technical (repo ruleset has 0 required approvals): the PR author
and the reviewer/merger must be different people, merged with the guarded-merge receipt kept in
the task thread. Audit trail = the PR history of the two policy files.

## What this does NOT cover

- The explicit `actions/cache*` usages (Kuikly mavenLocal, ...) — separate
  per-owner inventory; each has its own key literal to rotate.
- True backend deletion of poisoned entries (no API access) — entries die by eviction or key
  rotation only.
- Getting the epoch into extracted-entry keys — needs an upstream gradle/actions change
  (optional follow-up, explicitly not gated).
