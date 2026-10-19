# Android CI: D8 dex merging OOMs at the project heap ceiling

**Area:** build / CI
**Owner:** CC-Cata
**Verified at:** 2026-10-18
**Status:** current

## Symptom

`Android compile & tests` fails intermittently with

```
> Task :androidApp:mergeLibDexConnectedTest FAILED
ERROR: D8: java.lang.OutOfMemoryError: Java heap space
com.android.builder.dexing.DexArchiveMergerException: Error while merging dex archives
```

The same commit **passes when re-run with no code change**. That is the signature: the build is
sitting exactly at its heap ceiling, not hitting a defect.

## Rule out the machine before touching the build

Check the job summary's `===== Memory =====` block. In the 2026-10-18 failure the runner reported
**15 GiB total with 12 GiB available** at the moment D8 OOM'd. A machine with that much free memory
is not the constraint — the JVM heap is.

Corollary worth internalising: **a green re-run is weak evidence.** It shows the build can fit, not
that it will. Treat "passed on retry" as probability of success, not as proof of a fix.

## Where the ceiling comes from

`compose/gradle.properties` sets

```
org.gradle.jvmargs=-Xmx4g -Xss4m ...
```

4 GiB is sized for **one Kotlin/Native framework link per Gradle invocation**, not for dex merging.
The Android job's "Build Android connected-test APKs" step assembles **three** APKs in one
invocation:

```
:shared:assembleDebugAndroidTest
:androidApp:assembleConnectedTest
:androidApp:assembleConnectedTestAndroidTest
```

so D8 merges all of their dex inside that same 4 GiB.

## Raising the heap without inflating local builds

Raise it **for that CI step only**, via `$GRADLE_USER_HOME/gradle.properties`, which outranks the
project file. This is the mechanism `ios-simulator-app.yml` and `ios-release.yml` already use to
lift the identical ceiling for their builds:

```bash
GUH="${GRADLE_USER_HOME:-$HOME/.gradle}"
mkdir -p "$GUH"
# drop any previous line first: setup-gradle may restore this directory
if [ -f "$GUH/gradle.properties" ]; then
  grep -v '^org\.gradle\.jvmargs=' "$GUH/gradle.properties" > "$GUH/gradle.properties.tmp" || true
  mv "$GUH/gradle.properties.tmp" "$GUH/gradle.properties"
fi
printf '%s\n' 'org.gradle.jvmargs=-Xmx6g -Xss4m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' >> "$GUH/gradle.properties"
```

Do **not** raise `compose/gradle.properties` itself: developer machines are the reason it is
conservative, and a bigger default makes local builds compete for RAM on laptops that have nowhere
near 15 GiB.

The de-duplication matters — blindly appending stacks a second `org.gradle.jvmargs=` line every run
once a future `setup-gradle` step restores the directory.

## If it OOMs again

Do not simply raise the number further. Split the connected-test assemble into separate Gradle
invocations so D8 never merges all three APKs at once; that reduces the peak rather than paying for
it in RAM.
