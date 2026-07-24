# CI Gradle cache: epoch rotation & quarantine runbook

One page. Origin: the 2026-07-23 shared-cache poisoning incident (truncated AGP jar + broken KSP
loader inside one Blacksmith `gradle-dependencies-*` entry deterministically failed every Android
compile; attribution in #proj-chat task #781, design review in task #786).

## What exists

- `.github/gradle-cache-epoch` — exactly one line, `[A-Za-z0-9_.-]{1,32}`. Exported to every
  `setup-gradle` step as `GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX=<epoch>-`. Changing it makes all
  top-level Gradle User Home cache keys start a fresh family (first run per job re-saves, later
  runs hit the clean family). NOTE (verified against gradle/actions@v5 source): extracted-entry
  keys (`gradle-dependencies-*`, `gradle-transforms-*`, ...) are NOT covered by the prefix.
- `.github/gradle-cache-quarantine` — incident-response file, EMPTY in steady state. Allowlisted
  bundle names (`dependencies`, `transforms`), one per line. Listed bundles are exported as
  `GRADLE_BUILD_ACTION_SKIP_RESTORE=<b1,b2>`: their poisoned entries stop being restored, at the
  cost of re-resolving that bundle every run in every wired job. A quarantine line must stay until
  the poisoned bundle key rotates naturally (its hashed file-set changes); this is a quarantine,
  not a purge — the backend entry remains until eviction.
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

- The 21 explicit `actions/cache*` usages (Kuikly mavenLocal, OHOS SDK, ...) — separate
  per-owner inventory; each has its own key literal to rotate.
- True backend deletion of poisoned entries (no API access) — entries die by eviction or key
  rotation only.
- Getting the epoch into extracted-entry keys — needs an upstream gradle/actions change
  (optional follow-up, explicitly not gated).
