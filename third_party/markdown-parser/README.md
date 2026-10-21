# JetBrains Markdown parser

This directory vendors the common Kotlin sources of JetBrains Markdown
`0.7.5` so Android and iOS compile the same parser implementation.

## Frozen upstream

- Repository: <https://github.com/JetBrains/markdown>
- Upstream commit: `52368fb17e1d0faf58e2f954c8d57d034a1f2f98`
- Botiverse fork: <https://github.com/botiverse/jetbrains-markdown>
- Fork branch: `raft/0.7.5-mobile`
- Fork commit: `295290f8fa81365fd6cdb9e0ce2ca9a5b45c9018`
- Maven coordinate: `org.jetbrains:markdown:0.7.5`
- Source archive: `markdown-0.7.5-sources.jar`
- Source archive SHA-256:
  `dcd211f56ed9cc0ce28ead126ee9ebecb0e311b3adb968e9538add45b7f76e46`
- Imported payload: 106 files: 99 common `.kt` sources, three `.flex` lexer
  definitions, and one Ruby entity generator from the source archive, plus
  the frozen upstream commit's one JVM and two Kotlin/Native `.kt` actual
  implementations
- License: Apache License 2.0; see [LICENSE](LICENSE)

The 103 imported common source and generator files are byte-identical between
the source archive and the frozen upstream commit before applying the six
declared fork changes below. The three platform actual files are copied
byte-for-byte from that same commit. The complete vendored source payload
matches the corresponding paths at the Botiverse fork commit above.

## Local divergence

Only these vendored files intentionally differ from frozen upstream:

- `src/commonMain/kotlin/org/intellij/markdown/flavours/gfm/GFMMarkerProcessor.kt`
- `src/commonMain/kotlin/org/intellij/markdown/parser/MarkerProcessor.kt`
- `src/commonMain/kotlin/org/intellij/markdown/parser/markerblocks/impl/ParagraphMarkerBlock.kt`
- `src/commonMain/kotlin/org/intellij/markdown/flavours/gfm/lexer/_GFMLexer.kt`
- `src/commonMain/kotlin/org/intellij/markdown/flavours/space/lexer/_SFMLexer.kt`
- `src/commonMain/kotlin/org/intellij/markdown/lexer/_MarkdownLexer.kt`

`GFMMarkerProcessor` adds a typed table-provider constructor seam whose default
remains the stock `GitHubTableMarkerProvider()`. Slock supplies a composed
provider from `RaftGFMFlavourDescriptor`; the upstream provider implementation
stays byte-exact, while Raft's policy recognizes chat-friendly tables whose
separator immediately follows a paragraph line without rewriting source text.

`MarkerProcessor` and `ParagraphMarkerBlock` add a paragraph
lazy-continuation policy seam, exposed as a second `GFMMarkerProcessor`
constructor parameter with a stock-permissive default (the full CommonMark and
GFM spec suites pass unchanged on the fork). Slock supplies a policy from
`RaftGFMFlavourDescriptor` that ends a paragraph when a lazily-continued line
drops a block-quote constraint, so `> quote` followed by an unmarked reply
line renders as a quote plus a plain paragraph, matching the web chat
dialect; list lazy continuation is unaffected.

The three generated lexer files contain only the mechanical Kotlin migration
`String.toLowerCase()` to `String.lowercase()`. This is required because this
repository treats the former API's deprecation as a compilation error; it does
not change the parser grammar or lexer state machine.

## Rebuilding the vendor tree

1. Download the `org.jetbrains:markdown:0.7.5` sources archive from Maven
   Central and verify the SHA-256 above.
2. Extract `commonMain/org/intellij/markdown/**/*.kt`, the three lexer inputs
   `flavours/gfm/lexer/gfm.flex`, `flavours/space/lexer/sfm.flex`, and
   `lexer/markdown.flex`, plus `html/entities/gen_entities.rb`, into
   `src/commonMain/kotlin`. Verify 103 common files: 99 Kotlin and four
   generator inputs.
3. Copy the commit's `jvmMain` actual file and two `nativeMain` actual files
   into the matching vendor source-set directories; verify 106 total files.
4. Compare all imported files with upstream commit
   `52368fb17e1d0faf58e2f954c8d57d034a1f2f98`.
5. Reapply and independently review the six local-divergence files listed
   above, or copy those files from Botiverse fork commit
   `295290f8fa81365fd6cdb9e0ce2ca9a5b45c9018`.

Build wiring lives in `compose/shared/build.gradle.kts`. The build may not
also depend on a binary Markdown parser, because duplicate parser classes
would make the compiled implementation ambiguous.
