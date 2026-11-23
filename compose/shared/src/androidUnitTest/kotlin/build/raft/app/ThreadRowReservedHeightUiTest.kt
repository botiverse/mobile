package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactScreenshot
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSummary
import build.raft.designsystem.text.RaftText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #proj-mobile:#201 on a real composition: a message row keeps its height while late content
 * resolves, so nothing above it moves on the reversed list.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ThreadRowReservedHeightUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.heightOf(tag: String): Int = onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test
    fun aScreenshotThatCannotBeReadKeepsItsBox() = runRobolectricComposeUiTest {
        val answer = CompletableDeferred<ByteArray?>()
        val reader =
            object : ArtifactScreenshotReader {
                override fun kept(cardId: String, fileId: String): ByteArray? = null

                override suspend fun read(cardId: String, fileId: String): ByteArray? = answer.await()
            }
        val summary =
            ArtifactSummary(
                id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
                title = "Q3 revenue report",
                description = "Revenue by region.",
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Server,
                authorName = "Lagoon",
                updatedAt = null,
                facts = ArtifactFacts(sizeBytes = 24_000),
                screenshot = ArtifactScreenshot(desktop = "of_desktop", phone = "of_phone"),
            )
        setContent {
            CompositionLocalProvider(LocalArtifactScreenshotReader provides reader) {
                RaftDesignSystemTheme {
                    Box(Modifier.width(360.dp).testTag("card")) {
                        ArtifactMessageCard(summary, onOpen = {})
                    }
                }
            }
        }
        waitForIdle()
        val whileRead = heightOf("card")
        answer.complete(null)
        waitForIdle()
        assertEquals(whileRead, heightOf("card"), "a screenshot that can't be read keeps the place it had while read")
    }

    @Test
    fun aPendingTranslationKeepsTheOriginalsHeightWithoutShowingIt() = runRobolectricComposeUiTest {
        var pending by mutableStateOf(true)
        // The placeholder pulses forever: idle never comes with the clock running on its own.
        mainClock.autoAdvance = false
        setContent {
            RaftDesignSystemTheme {
                Column(Modifier.width(360.dp).testTag("row")) {
                    ThreadMessageTranslationPendingFrame(pending = pending, alignEnd = false) {
                        Box(Modifier.fillMaxWidth().height(120.dp)) { RaftText("Original words") }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val whilePending = heightOf("row")
        assertEquals(0, onAllNodesWithText("Original words").fetchSemanticsNodes().size, "the untranslated text is not shown or read out")
        pending = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(whilePending, heightOf("row"), "the row keeps the original body's height while translating")
        assertTrue(onAllNodesWithText("Original words").fetchSemanticsNodes().isNotEmpty())
    }
}
