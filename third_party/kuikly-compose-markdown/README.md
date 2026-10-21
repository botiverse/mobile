# Kuikly Compose Markdown Fork

This directory vendors the Kuikly markdown renderer source so Slock can patch and
build it locally instead of consuming `com.tencent.kuiklybase:markdown` binaries
directly.

## Source

- Upstream project: <https://github.com/Kuikly-contrib/kuikly-compose-markdown>
- Maven coordinates inspected:
  - `com.tencent.kuiklybase:markdown:0.4.0`
  - `com.tencent.kuiklybase:markdown-android:0.4.0`
  - `com.tencent.kuiklybase:markdown:0.3.0-ohos`
- Source snapshot: `markdown-android-0.4.0-sources.jar`

The public GitHub repository currently contains only documentation, so this fork
is bootstrapped from the published Maven source artifact.

## Local Patch

The upstream Android AAR embeds classes under `kotlinx.collections.immutable.*`
inside `markdown-release.aar/classes.jar`. Android already brings
`org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm`, so using the upstream
AAR causes duplicate class failures.

This fork renames the bundled immutable collection implementation to:

```text
com.tencent.kuiklybase.markdown.shaded.immutable
```

Do not reintroduce the upstream AAR as an app/shared dependency unless that
embedded package is shaded or removed.

## Next Integration Step

The fork is intentionally source-only in this commit. The next step should add a
local Gradle module/publish script and wire `compose/shared` to the local fork.

