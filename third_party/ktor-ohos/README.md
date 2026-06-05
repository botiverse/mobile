# Ktor OHOS Fork

This folder documents Slock's reproducible path for enabling shared network
code on HarmonyOS/OHOS.

## Baseline

- Reference fork: `https://github.com/blackbbc/ktor.git`
- Reference branch: `3.0.3-ohos`
- Reference commit checked during investigation: `a8ef69a123932ca891237920e9ca4995596d30ac`
- Default Slock local version: `3.0.3-slock-ohos-001`

The reference fork is based on Ktor `3.0.3` and publishes as
`3.0.3-KBA-001`. It is not the same baseline as the current Android app, which
still uses Ktor `2.3.7`.

## What The Fork Adds

The useful part is Ktor itself, not Ktorfit.

The Ktor fork adds:

- `ktor-client:ktor-client-ohos`
- an `io.ktor.client.engine.ohos.Ohos` engine
- `ohosArm64` source sets and publications across the client stack
- OHOS cinterop definitions for libcurl and Harmony RCP
- `ktor-io`, `ktor-network`, and `ktor-utils` OHOS support
- Tencent Maven repositories and KBA Kotlin/coroutines/serialization versions

The OHOS engine exposes `OhosClientEngineConfig.useRcp`. In the reference fork
the property is documented as preferring Harmony RCP, but the default value in
code is currently `false`, so the engine uses curl unless configured otherwise.

The Ktorfit branch at
`https://github.com/Foso/Ktorfit/compare/master...blackbbc:Ktorfit:ohos-2.2.0`
is a much broader generator/plugin diff. Slock currently uses handwritten
`SlockApiClient`, so Ktorfit is not required for the next network milestone.
Keep it as a later option only if we decide to return to annotation-generated
API clients.

## Prepare Locally

```bash
scripts/prepare_ktor_ohos_fork.sh
```

By default this checks out the reference fork under:

```text
third_party/.workdirs/ktor-slock-ohos
```

Override the source repository after Slock creates its own fork:

```bash
SLOCK_KTOR_OHOS_SOURCE_URL=https://github.com/<slock-org>/ktor.git \
SLOCK_KTOR_OHOS_SOURCE_REF=slock-3.0.3-ohos \
scripts/prepare_ktor_ohos_fork.sh
```

Publish the minimal artifacts needed by shared network code:

```bash
scripts/prepare_ktor_ohos_fork.sh --publish
```

The script publishes:

- `io.ktor:ktor-client-core`
- `io.ktor:ktor-client-ohos`
- `io.ktor:ktor-client-content-negotiation`
- `io.ktor:ktor-serialization-kotlinx-json`
- `io.ktor:ktor-client-mock`
- transitive Ktor modules used by those artifacts

## Current Build Status

The prepare script has been verified to clone the reference fork and rewrite
the local version.

Direct compile currently reaches OHOS dependency resolution but is blocked by
missing KBA kotlinx artifacts:

```bash
cd third_party/.workdirs/ktor-slock-ohos
./gradlew :ktor-client:ktor-client-ohos:compileKotlinOhosArm64
```

Observed blockers:

- `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3-KBA-001`
- `org.jetbrains.kotlinx:kotlinx-io-core:0.5.4-KBA-001`

Gradle searched Maven Central, Tencent `maven-tencent`, Tencent
`maven-public`, and `mavenLocal`; none contained these coordinates on this
machine. That means the Ktor OHOS fork has the engine source and Gradle target,
but Slock still needs either:

- the private/internal Maven that contains the KBA kotlinx artifacts, or
- additional local forks/publications for the missing KBA kotlinx dependencies.

`Tencent-TDS/KuiklyBase-platform` is the right upstream dependency catalog for
this stack, but it does not close every Ktor gap by itself:

- it provides `kotlinx.serialization` `1.7.1-KBA-003`, which our compose OHOS
  build already resolves from Tencent Maven;
- it provides `kotlinx.coroutines` `1.8.0-KBA-001` and `atomicfu`
  `0.23.2-KBA-001`, matching Ktor fork expectations;
- it does not include a `kotlinx-io` patch;
- it does not include the exact `kotlinx.serialization` `1.7.3-KBA-001`
  version requested by `blackbbc/ktor 3.0.3-ohos`.

See `third_party/kuiklybase-platform/README.md` and
`scripts/prepare_kuiklybase_dependency.sh` for the reproducible patch workflow.
The remaining Ktor decision is whether to port KuiklyBase serialization forward
to `1.7.3-KBA-001` or adjust the Ktor fork to the available
`1.7.1-KBA-003`, plus how to provide `kotlinx-io:0.5.4-KBA-001`.

## Slock Integration Plan

Current state:

- `compose/shared/build.gradle.kts` uses Ktor `2.3.7`.
- `compose/shared/build.ohos.gradle.kts` excludes `**/network/**`, so OHOS
  does not compile the shared network package yet.
- Android uses `ktor-client-okhttp`.

Recommended sequence:

1. Publish the Ktor OHOS fork to `mavenLocal` or an internal Maven repository.
2. Add `mavenLocal()` and the internal repository to the OHOS compose build if
   needed.
3. Upgrade the `compose/shared` network dependencies to a single Ktor `3.0.3`
   fork version for both Android and OHOS, or isolate the upgrade behind a new
   network module before touching the Android app.
4. Remove the OHOS `kotlin.exclude("**/network/**")` only after
   `ktor-client-ohos` resolves.
5. Add an `ohosArm64Main` client factory that creates:

   ```kotlin
   HttpClient(Ohos) {
       engine {
           useRcp = true
       }
   }
   ```

6. Run a small shared `SlockApiClient` smoke request before moving repository
   implementations.

Do not switch Android release builds to the Ktor 3 fork in the same commit as
the OHOS smoke. Android should keep using OkHttp and existing request tests must
stay green.
