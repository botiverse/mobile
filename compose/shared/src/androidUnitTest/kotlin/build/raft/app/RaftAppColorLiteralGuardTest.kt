package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Dark mode guard: app UI takes its colours from the theme ([RaftColor] / designsystem
 * `RaftTheme.colors`), so a new `Color(0x…)`, `Color.White` / `Color.Black` or a pinned
 * `RaftUiThemeColors.RaftLight` in app code would stay light under the dark theme.
 *
 * A literal that must not follow the theme (photo / video viewers, scrims, drawing palettes,
 * fixed sticker art) carries a `raft-color:` comment on the same line saying why.
 */
class RaftAppColorLiteralGuardTest {
    @Test
    fun appUiHasNoUnannotatedColourLiterals() {
        val root = repositoryFile(AppSourceRoot)
        assertTrue(root.isDirectory, "app sources not found at ${root.path}")
        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file -> file.relativeTo(root).invariantSeparatorsPath in ExemptFiles }
                .filterNot { file -> file.relativeTo(root).invariantSeparatorsPath.startsWith("designtokens/") }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val code = line.substringBefore("//")
                        val flagged = ColourLiteral.containsMatchIn(code) || PinnedLightTheme.containsMatchIn(code)
                        if (flagged && !line.contains(Marker)) {
                            "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        if (offenders.isNotEmpty()) {
            fail(
                "Hardcoded colours in app UI do not follow dark mode. Use a RaftColor / RaftTheme.colors " +
                    "token, or mark an intentional literal with `// $Marker <why>`:\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    private fun repositoryFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.exists() }
            ?: File(start, relativePath)
    }

    private companion object {
        const val AppSourceRoot = "compose/shared/src/commonMain/kotlin/build/raft/app"
        const val Marker = "raft-color:"

        val ColourLiteral = Regex("""\bColor\(\s*0x[0-9A-Fa-f]+|\bColor\.(White|Black)\b""")
        val PinnedLightTheme = Regex("""RaftUiThemeColors\.RaftLight\b""")

        /**
         * Token definitions, the theme itself, fixed pixel-art data and debug / visual probe pages
         * (fixed colours on purpose).
         */
        val ExemptFiles =
            setOf(
                "RaftDesignTokens.kt",
                "RaftAppTheme.kt",
                "RaftPixelAvatarData.kt",
                "RaftPagerScrollProbePage.kt",
                "RaftTabPagerProbePage.kt",
                "RaftChildPageVisibilityProbePage.kt",
                "TokenPaletteVisualPage.kt",
                "RaftTypographyProbePage.kt",
                "RaftLucideIconGalleryPage.kt",
            )
    }
}
