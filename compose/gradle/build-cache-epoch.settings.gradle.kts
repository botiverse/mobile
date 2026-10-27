// CI-only: give the local build cache a directory named after the repository Gradle cache epoch
// (.github/gradle-cache-epoch, exported by .github/actions/gradle-cache-policy as
// RAFT_GRADLE_BUILD_CACHE_EPOCH). Unset (local builds) keeps Gradle's default directory.
//
// Why: gradle/actions/setup-gradle v6 saves caches/build-cache-*/* as its own cache entry whose
// key is a hash of the entry FILE NAMES, with no epoch in it. After an epoch rotation a fresh
// build of unchanged inputs produces the same file names, the save hits "entry with key already
// exists", and the new Gradle-home metadata points back at the old (possibly poisoned) blob.
// Putting the epoch in the directory name puts it in every file name, so a rotation always
// yields a new build-cache entry. Proven by the epoch guard jobs in gradle-cache-policy-lint.yml,
// which apply this same file.
val buildCacheEpoch = providers.environmentVariable("RAFT_GRADLE_BUILD_CACHE_EPOCH").orNull
if (!buildCacheEpoch.isNullOrBlank()) {
    require(Regex("[A-Za-z0-9_.-]{1,32}").matches(buildCacheEpoch)) {
        "RAFT_GRADLE_BUILD_CACHE_EPOCH must match [A-Za-z0-9_.-]{1,32}, got '$buildCacheEpoch'"
    }
    buildCache {
        local {
            directory = File(gradle.gradleUserHomeDir, "caches/build-cache-epoch-$buildCacheEpoch")
        }
    }
}
