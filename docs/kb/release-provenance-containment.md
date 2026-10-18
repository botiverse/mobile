# Does the package contain the fix? Read the build's source, not the version string

Owner: 辣条 · Verified at: 2026-10-18 · Status: current

Answering "is fix X in the build the user has?" has one reliable method: resolve the **exact source commit of that build**, then test commit containment. A version name (`1.13.1`) and a commit timestamp are both weaker than they look, and both have produced wrong answers in real support threads.

## Why the version string is not enough

A release branch is a stabilization boundary, not a snapshot that follows `main` (see `docs/android-release-sop.md` and `docs/mobile-paired-release-sop.md`). Product work keeps landing on `main`; only explicitly selected blocker fixes are merged into `release-*`. So:

- A fix merged to `main` is **not** in a release build unless it was brought into that release branch.
- A build made *later* than the fix can still lack it.
- Two builds with the same version name can carry different source.

Worked example from public history:

| Build | Workflow run | Ref | Build head | Contains `832f212a`? |
|---|---|---|---|---|
| iOS 1.13.0 (11300008) | `37793462480` | `release-1.13.0` | `cf511fd12` | **No** |
| iOS 1.13.1 (11301004) | `38152413529` | `release/1.13.1` | `ced0093e1` | **Yes** |
| Android 1.13.1 (11301005) | `38161888581` | `release/1.13.1` | `311af7d52` | **Yes** |

`832f212a` is the merge of PR #2873 on `main` (committed 2026-10-08 08:21Z). The 1.13.0 build head `cf511fd12` was committed later that day (13:45Z) and still does not contain it, because it lives on a different branch. Timestamp ordering alone would have said "later, so included" — which is wrong.

Note the ref names: `release-1.13.0` uses a dash, `release/1.13.1` and `release/1.14.0` use a slash. Copy the ref from the run instead of reconstructing it; guessing the separator fetches nothing and silently "confirms" the wrong thing.

## The procedure

1. **Identify the build's source.** In the release workflow, the `Resolve release parameters` job prints `version_name` / `version_code` and the checkout step names the ref and head SHA. `stamp run view <run-id> --repo botiverse/mobile` prints the ref and head; `--job <id> --log` shows the resolved parameters.
2. **Fetch that exact history** and test containment of the fix's merge commit:

   ```bash
   git fetch origin <ref-from-the-run>
   git merge-base --is-ancestor <fix-merge-sha> <run-head-sha> \
     && echo contained || echo absent
   ```

3. **Report the chain, not the conclusion alone**: versionCode → run id → ref → head SHA → contained/absent. Whoever reads it later can re-run the same four checks.

For a device report, add one more link: the installed app's versionCode must match the build you audited, otherwise the provenance you proved belongs to a different package.

## What ancestry proves — and what it does not

Proved: the fix's source commit is in the tree the build was cut from.

Not proved:

- **That the artifact is that build.** A side-loaded or older package can carry a different `versionCode`. Check the installed version, and when it matters, the artifact's SHA-256.
- **That the fix works.** Backport conflicts, feature flags, and platform-specific code paths can make the same source behave differently. Presence is a prerequisite for a retest, not a result.
- **That a cherry-pick is absent.** If a fix was cherry-picked onto the release branch instead of merged, the original merge SHA is not an ancestor even though the change is there. Look for the `(cherry picked from commit <sha>)` trailer in the release-branch log, or compare the patch content. Do not conclude "missing" from ancestry alone when backports are in play.

## Answering a support question

Give the requester three facts and one instruction:

1. the build's source SHA and workflow run;
2. whether the fix commit is an ancestor of it;
3. what that means for their package (`contains` / `not in this build` / `cannot tell — the installed versionCode does not match`);
4. what to retest on, if a newer build exists.

Do not translate "not contained" into "the fix failed". Those are different statements, and only a retest on a build that contains the fix can separate them.
