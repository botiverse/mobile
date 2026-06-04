# OHOS SQLDelight Fork Stack

Slock vendors the OHOS SQLDelight native-driver stack as git submodules under
`third_party/`:

- `third_party/stately` -> `bytemain/Stately`, branch `slock-2.1.0-ohos`
- `third_party/sqliter` -> `bytemain/SQLiter`, branch `slock-1.3.3-ohos`
- `third_party/sqldelight` -> `bytemain/sqldelight`, branch `slock-2.1.0-ohos`

The pinned commits for the first working stack are:

- `abdbeda` Stately OHOS publications
- `a0d2ee5` SQLiter OHOS driver
- `08df192` SQLDelight OHOS native driver

## Why Three Repos

SQLDelight OHOS support is not only a SQLite C rebuild. The native driver also
needs OHOS variants from its native dependency chain:

1. Stately publishes `stately-strict` and `stately-concurrency` for
   `ohosArm64`.
2. SQLiter publishes `sqliter-driver` for `ohosArm64`, including the SQLite
   cinterop layer.
3. SQLDelight publishes/compiles `native-driver` for `ohosArm64` against those
   Touchlab artifacts.

The target application architecture is still local-first:

- UI observes shared state holder / ViewModel.
- Repository exposes local DAO flows.
- Remote data sources fetch/send/realtime.
- Remote results are written into the DAO.
- DAO changes drive UI updates.

Do not make shared business stores call raw platform SQLite directly. Use a
typed local data source backed by SQLDelight once the smoke DB is in place.

## Checkout

```bash
git submodule update --init --recursive third_party/stately third_party/sqliter third_party/sqldelight
```

## Local Verification

Publish the dependency stack to Maven local in this order:

```bash
cd third_party/stately
./gradlew \
  :stately-strict:publishKotlinMultiplatformPublicationToMavenLocal \
  :stately-strict:publishOhosArm64PublicationToMavenLocal \
  :stately-concurrency:publishKotlinMultiplatformPublicationToMavenLocal \
  :stately-concurrency:publishOhosArm64PublicationToMavenLocal

cd ../sqliter
./gradlew \
  :sqliter-driver:publishKotlinMultiplatformPublicationToMavenLocal \
  :sqliter-driver:publishOhosArm64PublicationToMavenLocal

cd ../sqldelight
./gradlew :sqldelight-gradle-plugin:publishToMavenLocal
./gradlew :drivers:native-driver:compileKotlinOhosArm64
```

The verified `sqldelight` native-driver compile resolves these local artifacts:

- `co.touchlab:stately-concurrency-ohosarm64:2.1.0-KBA-001`
- `co.touchlab:stately-strict-ohosarm64:2.1.0-KBA-001`
- `co.touchlab:sqliter-driver-ohosarm64:1.3.3-KBA-001`

## Next Step

Add a minimal `compose/shared` SQLDelight smoke database:

- Android actual driver: `AndroidSqliteDriver`.
- OHOS actual driver: SQLDelight `NativeSqliteDriver`.
- Smoke DAO: insert/query/observe one table from shared repository tests.

Only after this smoke compiles on Android and OHOS should real app schemas move
from the Android app module into shared.
