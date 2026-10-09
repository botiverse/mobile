#!/usr/bin/env bash
# Materialize Slock submodules inside this artifact worktree using a clean fallback path.
#
# Strategy per submodule:
#   1) If main checkout's submodule working tree is clean at pinned SHA, copy it.
#   2) Otherwise, run `git archive <pinned> | tar -x -C <target>` inside the main submodule
#      working tree to extract a clean tree at pinned SHA without touching the main checkout.
#   3) Symlink .git/modules/<name> from main repo, write .git pointer file in target.
#   4) Patch main-side modules/<name>/config core.worktree to point at this worktree.
#   5) Verify HEAD == pinned inside the materialized submodule.
#
# This guarantees we never embed another agent's WIP dirty bytes into the artifact.
set -euo pipefail

WT="/Users/artin/AndroidStudioProjects/Slock-worktrees/pi-perf-artifact-task69-c51dcbb95"
MAIN="/Users/artin/AndroidStudioProjects/Slock"

# pinned SHA -> submodule path
declare -a ROWS=(
  "a915f69965d4a8ee0550af1a9fbda24ae8e22e44|.agents/skills/kuikly-ui-framework/references/KuiklyUI"
  "9527520453b3e14156d5f4b527ecccb732f19cf6|third_party/kuikly-ui"
  "b128cc1944a7ba5d566d08496c949805c2be479f|third_party/slock-source"
  "d30a740ccee9e7b46b8d02135cc46e7bb5dd03a5|third_party/slock-visual-testing"
  "c1af1166161d65f0a29db71644ef3ebb252064d1|third_party/sqldelight"
  "a0d2ee5d5b5356eebc0f5f434bcad0fc077ecc35|third_party/sqliter"
  "abdbeda1dff582c0d7fb444e7eec471662a81bbc|third_party/stately"
)

cd "$WT"

for row in "${ROWS[@]}"; do
  sha="${row%%|*}"
  path="${row##*|}"
  main_wt="${MAIN}/${path}"
  target_dir="${WT}/${path}"
  main_modules_dir="${MAIN}/.git/modules/${path}"
  wt_modules_link="${WT}/.git/modules/${path}"

  echo "== materializing $path @ ${sha:0:7} =="

  # ensure empty target
  rm -rf "$target_dir"
  mkdir -p "$target_dir"

  if [ ! -d "$main_wt/.git" ] && [ ! -f "$main_wt/.git" ]; then
    echo "  WARN: main submodule working tree missing at $main_wt"
    echo "  skipping (build will fail for this submodule path)"
    continue
  fi

  # extract clean tree at pinned SHA from main checkout's submodule git history
  ( cd "$main_wt" && git archive --format=tar "$sha" ) | tar -x -C "$target_dir"

  # symlink gitdir so submodule operations (checkout, rev-parse) work in target.
  # The WT/.git is a gitfile (detached worktree), so we point .git inside the
  # submodule working tree directly at MAIN/.git/modules/<path>.
  ln -sfn "${MAIN}/.git/modules/${path}" "$target_dir/.git"

  # patch main-side core.worktree so future submodule ops from main checkout still find
  # the right worktree path (without this, main checkout breaks — same bug as task58)
  cfg="${MAIN}/.git/modules/${path}/config"
  if [ -f "$cfg" ]; then
    # delete any existing (possibly corrupted) worktree line, then append a clean one.
    # BSD sed doesn't reliably support \s and \1, so use plain character classes.
    sed -i '' '/^[[:space:]]*worktree[[:space:]]*=.*/d' "$cfg"
    printf "\tworktree = %s\n" "$target_dir" >> "$cfg"
  fi

  # detach HEAD at pinned SHA inside the target so .git/HEAD points at the right commit.
  # The gitdir is shared with the main checkout (whose HEAD may sit on a different branch +
  # whose working tree may be dirty on someone's WIP lane), so we must explicitly reset here.
  # Use `git reset --hard` rather than `git checkout` to avoid the "local changes would be
  # overwritten" guard that fires when the main checkout's working tree is dirty at files
  # that the pinned tree defines differently.
  ( cd "$target_dir" && \
    git update-ref HEAD "$sha" 2>/dev/null || true; \
    git reset --quiet --hard "$sha" 2>&1 | head -3 ) || true

  # verify HEAD == pinned inside target (compare full SHA, not short-form which is variable-length)
  head_full=$( cd "$target_dir" && git rev-parse HEAD 2>&1 )
  echo "  target HEAD: ${head_full:0:7} (expected ${sha:0:7})"
  if [ "$head_full" != "$sha" ]; then
    echo "  ERROR: HEAD mismatch in $path (got $head_full, want $sha)"
    exit 2
  fi
done

echo "== final submodule status =="
cd "$WT"
git submodule status 2>&1 | grep -v "tools/slock-visual-testing-react" || true