#!/usr/bin/env bash
# Steps for the build-cache epoch guard jobs in .github/workflows/gradle-cache-policy-lint.yml.
#
# gradle/actions/setup-gradle v6 saves caches/build-cache-*/* as a separate cache entry keyed
# only by a hash of the entry FILE NAMES. After a cache-epoch rotation, a fresh build with
# unchanged inputs produces the same names, so the save hits "entry with key already exists" and
# the new Gradle-home metadata points back at the pre-rotation blob. The epoch therefore has to
# be part of the build-cache directory name (compose/gradle/build-cache-epoch.settings.gradle.kts).
# These steps build a tiny probe project that applies that same settings plugin, so the guard
# exercises the real directory naming, the real Gradle build cache and the real setup-gradle
# save/restore.
#
# Usage: gradle_cache_epoch_guard.sh build|poison|assert-poison-restored|assert-clean-restored
# Needs GRADLE_USER_HOME, RAFT_GRADLE_BUILD_CACHE_EPOCH, GITHUB_WORKSPACE, RUNNER_TEMP.
set -euo pipefail

: "${GRADLE_USER_HOME:?}" "${RAFT_GRADLE_BUILD_CACHE_EPOCH:?}" "${GITHUB_WORKSPACE:?}" "${RUNNER_TEMP:?}"
cache_dir="$GRADLE_USER_HOME/caches/build-cache-epoch-$RAFT_GRADLE_BUILD_CACHE_EPOCH"

entries() {
  # Gradle local build-cache entries are files named by a 32-hex cache key.
  find "$cache_dir" -maxdepth 1 -type f -regextype posix-extended -regex '.*/[0-9a-f]{32}' 2>/dev/null | sort
}

case "${1:-}" in
  build)
    if [ -e "$cache_dir" ]; then
      echo "FAIL: $cache_dir already exists before the probe build (expected a cold start)"; exit 1
    fi
    probe="$RUNNER_TEMP/build-cache-epoch-probe"
    rm -rf "$probe"
    mkdir -p "$probe/src/main/java/probe"
    printf 'apply(from = "%s/compose/gradle/build-cache-epoch.settings.gradle.kts")\nrootProject.name = "build-cache-epoch-probe"\n' \
      "$GITHUB_WORKSPACE" > "$probe/settings.gradle.kts"
    printf 'plugins { java }\n' > "$probe/build.gradle.kts"
    printf 'package probe;\npublic class Probe { public static int answer() { return 42; } }\n' \
      > "$probe/src/main/java/probe/Probe.java"
    (cd "$GITHUB_WORKSPACE/compose" && ./gradlew --project-dir "$probe" --no-daemon --build-cache -q compileJava)
    count="$(entries | wc -l)"
    [ "$count" -ge 1 ] || { echo "FAIL: probe build stored no entry in $cache_dir"; ls -la "$GRADLE_USER_HOME/caches" || true; exit 1; }
    echo "ok(build): $count build-cache entr(y/ies) in caches/build-cache-epoch-$RAFT_GRADLE_BUILD_CACHE_EPOCH"
    ;;
  poison)
    while IFS= read -r entry; do
      printf 'POISON' > "$entry"
      echo "poisoned $(basename "$entry")"
    done < <(entries)
    ;;
  assert-poison-restored)
    # Same epoch: the seeded (poisoned) entry must come back. This is the "restore works"
    # control; without it a green rotated reader could just mean nothing restores at all.
    mapfile -t restored < <(entries)
    [ "${#restored[@]}" -ge 1 ] || { echo "FAIL: same-epoch reader restored no build-cache entry"; exit 1; }
    for entry in "${restored[@]}"; do
      [ "$(cat "$entry")" = POISON ] || { echo "FAIL: same-epoch entry $(basename "$entry") is not the seeded one"; exit 1; }
    done
    echo "ok(same-epoch): ${#restored[@]} seeded build-cache entr(y/ies) restored"
    ;;
  assert-clean-restored)
    # Rotated epoch: the rotated writer's own entry must come back, and never the seed's poison.
    mapfile -t restored < <(entries)
    [ "${#restored[@]}" -ge 1 ] || { echo "FAIL: rotated reader restored no build-cache entry"; exit 1; }
    for entry in "${restored[@]}"; do
      if [ "$(head -c 6 "$entry")" = POISON ]; then
        echo "FAIL: pre-rotation poisoned build-cache entry $(basename "$entry") restored after the epoch bump"; exit 1
      fi
    done
    if grep -rlq '^POISON$' "$GRADLE_USER_HOME/caches" 2>/dev/null; then
      echo "FAIL: poisoned content restored somewhere under caches/ after the epoch bump"
      grep -rl '^POISON$' "$GRADLE_USER_HOME/caches" || true
      exit 1
    fi
    echo "ok(rotated): ${#restored[@]} fresh build-cache entr(y/ies) restored, no pre-rotation poison"
    ;;
  *)
    echo "usage: $0 build|poison|assert-poison-restored|assert-clean-restored" >&2; exit 2
    ;;
esac
