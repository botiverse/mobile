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
 * Message code blocks with syntax colours (artin task #20 lane K): Kotlin, Python,
 * TypeScript and shell fences, plus a fence with no language that stays plain, in light
 * and dark. Reports land in `build/reports/home-screenshots/`; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadCodeHighlightScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun codeBlocksLight() = capture("thread.code-highlight", RaftAppAppearance.Light)

    @Test fun codeBlocksDark() = capture("thread.code-highlight.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(
            name,
            appearance,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { CodeBlockRows() }
            }
        }
}

private val fence = "```"

private val kotlinAndPython =
    """
    Kotlin and Python:
    ${fence}kotlin
    @Composable
    fun Greeting(name: String) {
        val count = 42 // shown once
        Text("Hello, ${'$'}name")
    }
    $fence
    ${fence}py
    def area(r: float) -> float:
        # pi times r squared
        return 3.14 * r ** 2
    $fence
    """.trimIndent()

private val typescriptShellPlain =
    """
    TypeScript, shell and plain:
    ${fence}ts
    const url = "https://raft.build/docs";
    export async function load(id: number) {
      return await fetch(`${'$'}{url}/${'$'}{id}`);
    }
    $fence
    ${fence}bash
    if [ -z "${'$'}TOKEN" ]; then echo "missing"; fi
    $fence
    $fence
    plain text keeps one colour: if return 42
    $fence
    """.trimIndent()

@Composable
private fun CodeBlockRows() {
    val rows =
        listOf(
            RaftThreadMessageItem(
                id = "kotlin-python",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-24T09:40:00Z",
                body = kotlinAndPython,
            ),
            RaftThreadMessageItem(
                id = "ts-shell-plain",
                authorId = "user-artin",
                authorName = "artin",
                authorKind = "human",
                avatarLabel = "A",
                timestampLabel = "2026-10-24T09:41:00Z",
                body = typescriptShellPlain,
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
                timestampLabel = "9:${message.timestampLabel.substring(14, 16)} AM",
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = { _, _ -> },
                onOpenMarkdownUrl = {},
                isMarkdownTagValid = { false },
            )
        }
    }
}
