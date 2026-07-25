# JetBrains Markdown parser

This directory vendors the common Kotlin sources of JetBrains Markdown
`0.7.5` so Android, iOS, and OHOS compile the same parser implementation.

## Frozen upstream

- Repository: <https://github.com/JetBrains/markdown>
- Upstream commit: `52368fb17e1d0faf58e2f954c8d57d034a1f2f98`
- Maven coordinate: `org.jetbrains:markdown:0.7.5`
- Source archive: `markdown-0.7.5-sources.jar`
- Source archive SHA-256:
  `dcd211f56ed9cc0ce28ead126ee9ebecb0e311b3adb968e9538add45b7f76e46`
- Imported payload: 102 `.kt` files: 99 common sources from the archive's
  `org/intellij/markdown` tree, plus the frozen upstream commit's one JVM and
  two Kotlin/Native actual implementations
- License: Apache License 2.0; see [LICENSE](LICENSE)

The 99 imported common sources are byte-identical between the source archive
and the frozen upstream commit. The three platform actual files are copied
byte-for-byte from that same commit.

## Local divergence

Only these vendored files intentionally differ from frozen upstream:

- `src/commonMain/kotlin/org/intellij/markdown/flavours/gfm/GFMMarkerProcessor.kt`
- `src/commonMain/kotlin/org/intellij/markdown/flavours/gfm/table/GitHubTableMarkerProvider.kt`
- `src/commonMain/kotlin/org/intellij/markdown/flavours/gfm/lexer/_GFMLexer.kt`
- `src/commonMain/kotlin/org/intellij/markdown/flavours/space/lexer/_SFMLexer.kt`
- `src/commonMain/kotlin/org/intellij/markdown/lexer/_MarkdownLexer.kt`

The first two add an optional table-continuation callback. Its default value is null,
which preserves the stock GFM behavior. Slock supplies the callback from
`SlockGFMFlavourDescriptor` to recognize chat-friendly tables whose separator
immediately follows a paragraph line, without rewriting the source text.

The three generated lexer files contain only the mechanical Kotlin migration
`String.toLowerCase()` to `String.lowercase()`. This is required because this
repository treats the former API's deprecation as a compilation error; it does
not change the parser grammar or lexer state machine.

## Rebuilding the vendor tree

1. Download the `org.jetbrains:markdown:0.7.5` sources archive from Maven
   Central and verify the SHA-256 above.
2. Extract only `commonMain/org/intellij/markdown/**/*.kt` into
   `src/commonMain/kotlin` and verify that exactly 99 Kotlin files exist.
3. Copy the commit's `jvmMain` actual file and two `nativeMain` actual files
   into the matching vendor source-set directories; verify 102 total files.
4. Compare all imported files with upstream commit
   `52368fb17e1d0faf58e2f954c8d57d034a1f2f98`.
5. Reapply and independently review the five local-divergence files listed
   above.

Build wiring lives in `compose/shared/build.gradle.kts` and
`compose/shared/build.ohos.gradle.kts`. Neither build may also depend on a
binary Markdown parser, because duplicate parser classes would make the
compiled implementation ambiguous.
