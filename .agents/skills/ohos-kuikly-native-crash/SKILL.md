---
name: ohos-kuikly-native-crash
description: Investigate and fix Slock OHOS Kuikly/Kotlin Native crashes and unreadable libshared.so PC stacks. Use when HiLog shows SlockKuiklyException, KuiklyException, libshared.so native frames, FileFailedToInitializeException, missing Kotlin method names, suspected OHOS symbol stripping/obfuscation, or crashes opening Kuikly/KMP pages on HarmonyOS/OpenHarmony.
---

# OHOS Kuikly Native Crash

Use this skill when an OHOS log contains Kuikly/Kotlin Native frames that look
unsymbolicated, for example:

```text
SlockKuiklyException ... #00 pc 0000000001d1cb57 libshared.so ...
SlockKuiklyException ... #01 pc 0000000001d41be7 libshared.so ...
```

Do not assume this means obfuscation is enabled. First prove whether symbols
exist locally, then symbolize the PCs with the exact `libshared.so` BuildID.

## Project Paths

Run from the repository root unless noted:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
```

Useful paths:

- OHOS app: `compose/ohosApp`
- OHOS build profile: `compose/ohosApp/entry/build-profile.json5`
- KMP OHOS debug shared library:
  `compose/shared/build/bin/ohosArm64/debugShared/libshared.so`
- Packaged/intermediate shared libraries:
  - `compose/ohosApp/entry/libs/arm64-v8a/libshared.so`
  - `compose/ohosApp/entry/build/default/intermediates/stripped_native_libs/default/arm64-v8a/libshared.so`
- DevEco LLVM tools:
  `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin`

## Triage Workflow

1. Read the task thread and get the exact HiLog file path.
2. Check whether debug obfuscation/stripping is actually enabled:

```bash
sed -n '1,80p' compose/ohosApp/entry/build-profile.json5
file \
  compose/ohosApp/entry/libs/arm64-v8a/libshared.so \
  compose/ohosApp/entry/build/default/intermediates/stripped_native_libs/default/arm64-v8a/libshared.so \
  compose/shared/build/bin/ohosArm64/debugShared/libshared.so
```

Expected debug configuration:

- `arkOptions.obfuscation.ruleOptions.enable = false`
- `nativeLib.debugSymbol.strip = false`
- `file ... libshared.so` prints `with debug_info, not stripped`

If the local `libshared.so` is unstripped but HiLog is unreadable, the problem
is the Kuikly exception callback/logging path, not necessarily missing symbols.

3. Confirm BuildID parity across local SOs:

```bash
file compose/ohosApp/entry/libs/arm64-v8a/libshared.so
file compose/shared/build/bin/ohosArm64/debugShared/libshared.so
```

Use a local SO only when its BuildID matches the device build you are analyzing.

4. Extract the top PCs from the log:

```bash
rg -n "SlockKuiklyException|KuiklyException|libshared\\.so|Uncaught Kotlin|FileFailed" \
  "<HiLog file path>"
```

5. Symbolize with DevEco `llvm-addr2line`:

```bash
/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/llvm-addr2line \
  -Cfpie compose/shared/build/bin/ohosArm64/debugShared/libshared.so \
  0x1d1cb57 0x1d41be7 0x1d41233
```

Use the `pc` value from each `libshared.so` frame. Do not use the large
`0x0 + <decimal>` value shown by HiLog.

## Interpreting File Initialization Failures

When symbolication shows:

```text
kotlin.native.internal.FileFailedToInitializeException
ThrowFileFailedToInitializeException
CallInitGlobalPossiblyLock
... some function in MyFile.kt
```

the named function may only be the first access into that Kotlin file. Kotlin
Native initializes the file's top-level declarations together. The real cause is
often a top-level `val` initializer in the same file that failed earlier.

Common OHOS/Konan risk patterns:

- top-level `Regex(...)`, especially Unicode property classes like `\p{L}` or
  `\p{N}`;
- large top-level maps/lists that construct complex objects before any screen
  uses them;
- top-level serializers or JSON formats that can be lazy instead;
- code that assumes platform support in a `val` initializer instead of inside a
  guarded function.

Prefer replacing risky top-level initializers with:

- `const val` for literals;
- small functions that compute on demand;
- `by lazy` only when the initializer itself is proven safe on OHOS;
- direct character checks (`Char.isLetter`, `Char.isLetterOrDigit`) instead of
  Unicode-property regex when validating names.

## Known Slock Example

Crash symptom:

```text
SlockKuiklyException ... libshared.so pc ...
```

Symbolicated stack:

```text
FileFailedToInitializeException
AgentRuntimeConfig.kt
getDefaultModel()
toMembersRuntimeConfig()
MembersAgentRuntimeConfigRows()
MembersAgentProfileTab()
```

Root cause:

- `AgentRuntimeConfig.kt` had a top-level
  `Regex("^[\\p{L}][\\p{L}\\p{N}_-]*$")`.
- Opening Agent Profile called `getDefaultModel()`, which initialized the whole
  file and failed before the runtime config UI could render.

Fix pattern:

- remove the top-level regex;
- use a small validation function:

```kotlin
private fun String.isValidAgentNameCharacters(): Boolean {
    val first = firstOrNull() ?: return false
    if (!first.isLetter()) return false
    return drop(1).all { char ->
        char.isLetterOrDigit() || char == '_' || char == '-'
    }
}
```

## Validation

Run the narrow test for the changed logic and the OHOS KMP compile:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:testDebugUnitTest --tests ai.slock.compose.model.AgentRuntimeConfigTest --console=plain
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 --console=plain
git diff --check -- <changed files>
git show --check --stat HEAD
```

The OHOS Gradle path may print the known KSP warning:

```text
ksp-2.0.21-1.0.27 is too old for kotlin-2.0.21-KBA-010
```

Treat it as a warning when the task ends with `BUILD SUCCESSFUL`.

## Reporting

Report:

- HiLog path and first relevant crash timestamp;
- whether debug obfuscation and native strip were enabled or disabled;
- BuildID parity for the local `libshared.so`;
- the exact `llvm-addr2line` command used;
- the top symbolicated frames;
- whether the root cause is missing symbols, Kuikly exception logging, a
  Kotlin/Native file initializer, or an actual native/Konan runtime crash;
- the validation commands that passed.
