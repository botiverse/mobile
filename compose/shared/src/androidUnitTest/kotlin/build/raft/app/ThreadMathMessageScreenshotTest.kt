package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task #775 (slock #10813): message rows with inline and display math, light and dark. The
 * first row is the common KaTeX subset (fractions, scripts, roots, sums and integrals with
 * limits, Greek, `\text` with CJK) inline and as `$$` blocks, including a matrix and an
 * `aligned` block wider than the bubble (scrolls); the second has prices that stay text and a
 * formula KaTeX rejects (its source in KaTeX's errorColor). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadMathMessageScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun mathLight() = capture("thread.math", RaftAppAppearance.Light)

    @Test
    fun mathDark() = capture("thread.math.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { MathRows() }
            }
        }
}

private val mathBody =
    """
    The area is ${'$'}\pi r^2${'$'}, so ${'$'}\frac{a}{b} + x_i^2 = \sqrt{2}${'$'} and ${'$'}\alpha, \beta, \Gamma${'$'} sit on the line; ${'$'}${'$'}E = mc^2${'$'}${'$'} too.

    ${'$'}${'$'}
    \sum_{i=1}^{n} i = \frac{n(n+1)}{2}, \quad \int_0^1 x^2\,dx = \frac{1}{3}
    ${'$'}${'$'}

    ${'$'}${'$'}
    \begin{pmatrix} a & b \\ c & d \end{pmatrix} \text{矩阵}
    ${'$'}${'$'}

    ${'$'}${'$'}
    \begin{aligned} f(x) &= (x+1)^2 + (x-1)^2 + (x+2)^2 + (x-2)^2 + (x+3)^2 \\ &= 5x^2 + 28 \end{aligned}
    ${'$'}${'$'}
    """.trimIndent()

private val pricesBody =
    // One line each: single-dollar math never crosses a line, but on one line the price's `$`
    // would pair with the formula's closing `$` (as on the web).
    "从 \$5 到 \$10, or \$5,\$10 each.\nAnd \$\\frac{1}{\\oops}\$ is not a formula."

@Composable
private fun MathRows() {
    val rows =
        listOf(
            RaftThreadMessageItem(
                id = "math",
                authorId = "user-alice",
                authorName = "Alice",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-22T16:40:00Z",
                body = mathBody,
            ),
            RaftThreadMessageItem(
                id = "prices",
                authorId = "user-alice",
                authorName = "Alice",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-22T16:41:00Z",
                body = pricesBody,
            ),
        )
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        rows.forEachIndexed { index, message ->
            val suppress = shouldSuppressCompactThreadAuthor(rows.getOrNull(index - 1), message, localOffsetMinutes = 0)
            ThreadMessageCard(
                message = message,
                messageBodyTextStyle = raftMessageBodyTextStyle("md"),
                compactConsecutiveMessages = true,
                suppressRepeatedAuthor = suppress,
                timestampLabel = "4:${message.timestampLabel.substring(14, 16)} PM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
            )
        }
    }
}
