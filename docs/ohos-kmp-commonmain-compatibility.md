# OHOS KMP commonMain Compatibility Notes

Owner lane: KMP/shared + OHOS compile hygiene

This note records commonMain patterns that can compile on Android/JVM but fail
on the OHOS Kotlin/Native target (`ohosArm64`, Kotlin `2.0.21-KBA-010`).
Use it when touching shared UI/store code that must build for Android and OHOS.

## Compile Gate

Use the shared OHOS compile gate from `compose/`:

```bash
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 --console=plain
```

Android-only compile is not enough for commonMain changes because the Android
JVM target accepts APIs and overload inference that Kotlin/Native does not.

## Known Differences

### Wall Clock

Do not call `System.currentTimeMillis()` from commonMain. It is JVM-only and is
not available to the OHOS Kotlin/Native compiler.

Use the existing expect/actual clock instead:

```kotlin
import build.raft.app.data.defaultNowMillis

val now = defaultNowMillis()
```

The actuals live in `androidMain` and `ohosArm64Main`.

### JVM Map Helpers

Do not rely on Java/JVM convenience methods such as `MutableMap.putIfAbsent` in
commonMain. They may compile on Android because the backing target is JVM, but
they are not part of the common Kotlin MutableMap API used by OHOS.

Use an explicit helper:

```kotlin
private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) {
    if (!containsKey(key)) {
        put(key, value)
    }
}
```

This keeps first-write-wins behavior without depending on JVM map extensions.

### Numeric Overload Inference

OHOS/Kotlin Native can be stricter around overloaded stdlib numeric helpers,
especially nested `maxOfOrNull` / `sumOf` lambdas where Android infers `Int`.

Prefer explicit, non-overloaded accumulation in commonMain:

```kotlin
val width = label.fold(0) { total, char ->
    total + if (char.code > 127) 14 else 8
}
```

This avoids ambiguous `sumOf` overloads and keeps the result type stable.

### Numeric Operators After Failed Type Inference

Some follow-on errors, such as missing `operator compareTo` or unresolved `/`,
are often secondary symptoms of an earlier unresolved JVM-only symbol or
ambiguous numeric type. Fix the root type/API first, then rerun the OHOS compile
gate before changing arithmetic logic.

## 2026-06-24 Fix Example

Task #215 hit three Android-passing but OHOS-failing commonMain patterns:

- `MembersPage.kt`: replaced `System.currentTimeMillis()` with
  `defaultNowMillis()`.
- `SlockMenu.kt`: replaced nested `sumOf` menu-width calculation with `fold`.
- `ThreadPage.kt`: replaced `MutableMap.putIfAbsent` with a commonMain helper.

After those changes:

```bash
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 --console=plain
```

passed.
