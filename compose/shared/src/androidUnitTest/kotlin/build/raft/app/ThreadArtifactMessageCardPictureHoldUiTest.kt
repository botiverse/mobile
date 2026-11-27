package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactScreenshot
import build.raft.app.data.ArtifactScreenshotReader
import build.raft.app.data.ArtifactSummary
import build.raft.app.thread.ARTIFACT_CARD_HELD_PICTURE_MAX_MILLIS
import build.raft.app.thread.ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS
import build.raft.app.thread.ArtifactCardCoverPendingTestTag
import build.raft.app.thread.ArtifactCardCoverStateKey
import build.raft.app.thread.ArtifactCardCoverTestTag
import build.raft.app.thread.ArtifactMessageCard
import build.raft.app.thread.LocalArtifactScreenshotReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web `artifactMessageCardPictureHold.behavior.test.tsx` parity (slock #16979, #wg-mobile-sync:#348):
 * when an Artifact message card that drew a 16:10 picture box receives a new version without a
 * screenshot, it holds the 16:10 box and re-polls the unpinned latest summary every 15s while
 * visible, dropping the box after 10 minutes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ThreadArtifactMessageCardPictureHoldUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val idA = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
    private val idB = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f"

    private fun cardSummary(
        id: String = idA,
        version: Int = 1,
        screenshot: ArtifactScreenshot? = null,
        excerpt: String? = null,
    ) = ArtifactSummary(
        id = id,
        title = "Q3 revenue report",
        description = "Revenue by region.",
        kind = if (excerpt != null) ArtifactKind.Markdown else ArtifactKind.Html,
        version = version,
        pinned = false,
        latestVersion = version,
        home = ArtifactHome.Server,
        authorName = "Lagoon",
        updatedAt = null,
        facts = ArtifactFacts(sizeBytes = 24_000),
        screenshot = screenshot,
        excerpt = excerpt?.let(::ArtifactExcerpt),
    )

    private fun ComposeUiTest.cardHeight(): Int = onNodeWithTag("card").fetchSemanticsNode().size.height

    private fun ComposeUiTest.pendingCoverState(): String? =
        onAllNodesWithTag(ArtifactCardCoverPendingTestTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config
            ?.let { cfg -> if (ArtifactCardCoverStateKey in cfg) cfg[ArtifactCardCoverStateKey] else null }

    @Test
    fun newVersionWithoutScreenshotHoldsBoxAndPollsEvery15sWhileVisibleUntilScreenshotArrives() =
        runRobolectricComposeUiTest {
            val v2Read = CompletableDeferred<ByteArray?>()
            val reader =
                object : ArtifactScreenshotReader {
                    override fun kept(cardId: String, fileId: String): ByteArray? =
                        if (fileId == "of_p1") byteArrayOf(1, 2, 3) else null

                    override suspend fun read(cardId: String, fileId: String): ByteArray? =
                        if (fileId == "of_p2") v2Read.await() else byteArrayOf(1, 2, 3)
                }
            var summary by mutableStateOf(
                cardSummary(version = 1, screenshot = ArtifactScreenshot(desktop = "of_d1", phone = "of_p1"))
            )
            var pageVisible = true
            val polls = mutableListOf<String>()

            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalArtifactScreenshotReader provides reader) {
                    RaftDesignSystemTheme {
                        Box(Modifier.width(360.dp).testTag("card")) {
                            ArtifactMessageCard(
                                summary = summary,
                                onOpen = {},
                                onRereadLatest = { polls += it },
                                isPageVisible = { pageVisible },
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            assertEquals(1, onAllNodesWithTag(ArtifactCardCoverTestTag, useUnmergedTree = true).fetchSemanticsNodes().size)
            val v1Height = cardHeight()

            runOnUiThread {
                summary = cardSummary(version = 2, screenshot = null)
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals(v1Height, cardHeight(), "held 16:10 box keeps the card's height across v1->v2")
            assertEquals("held", pendingCoverState())
            assertEquals(emptyList(), polls, "no immediate poll at t=0")

            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS - 1_000L)
            assertEquals(emptyList(), polls)

            mainClock.advanceTimeBy(1_000L)
            assertEquals(listOf(idA), polls)

            // Hidden page skips poll ticks without dropping the held box.
            pageVisible = false
            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS)
            assertEquals(listOf(idA), polls)
            assertEquals("held", pendingCoverState())

            // Visible again resumes 15s polling.
            pageVisible = true
            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS)
            assertEquals(listOf(idA, idA), polls)

            // New screenshot lands on the card: switches to loading and stops polling.
            runOnUiThread {
                summary = cardSummary(version = 2, screenshot = ArtifactScreenshot(desktop = "of_d2", phone = "of_p2"))
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals("loading", pendingCoverState())
            assertEquals(v1Height, cardHeight())

            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS * 2)
            assertEquals(listOf(idA, idA), polls, "polling stops once a screenshot arrives")
        }

    @Test
    fun heldBoxDropsAndStopsPollingAfterTenMinutes() = runRobolectricComposeUiTest {
        val reader =
            object : ArtifactScreenshotReader {
                override fun kept(cardId: String, fileId: String): ByteArray? = byteArrayOf(1, 2, 3)

                override suspend fun read(cardId: String, fileId: String): ByteArray? = byteArrayOf(1, 2, 3)
            }
        var summary by mutableStateOf(
            cardSummary(version = 1, screenshot = ArtifactScreenshot(desktop = "of_d1", phone = "of_p1"))
        )
        val polls = mutableListOf<String>()

        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalArtifactScreenshotReader provides reader) {
                RaftDesignSystemTheme {
                    Box(Modifier.width(360.dp).testTag("card")) {
                        ArtifactMessageCard(
                            summary = summary,
                            onOpen = {},
                            onRereadLatest = { polls += it },
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        val withCoverHeight = cardHeight()

        runOnUiThread {
            summary = cardSummary(version = 2, screenshot = null)
            Snapshot.sendApplyNotifications()
        }
        mainClock.advanceTimeByFrame()
        assertEquals("held", pendingCoverState())

        // Advance through the full 10-minute cap plus one frame for recomposition.
        mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_MAX_MILLIS)
        mainClock.advanceTimeByFrame()
        assertEquals(null, pendingCoverState(), "held box drops once 10 minutes elapse")
        assertTrue(cardHeight() < withCoverHeight, "card height collapses after held box drops")
        val pollsAtLapse = polls.size
        assertTrue(pollsAtLapse > 0)

        mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS * 4)
        assertEquals(pollsAtLapse, polls.size, "polling stops after 10-minute lapse")
    }

    @Test
    fun cardWithoutPriorPictureOrWithMarkdownExcerptOrSwitchedIdDoesNotHoldOrPoll() =
        runRobolectricComposeUiTest {
            val reader =
                object : ArtifactScreenshotReader {
                    override fun kept(cardId: String, fileId: String): ByteArray? = byteArrayOf(1, 2, 3)

                    override suspend fun read(cardId: String, fileId: String): ByteArray? = byteArrayOf(1, 2, 3)
                }
            var summary by mutableStateOf(cardSummary(id = idA, version = 1, screenshot = null))
            val polls = mutableListOf<String>()

            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalArtifactScreenshotReader provides reader) {
                    RaftDesignSystemTheme {
                        Box(Modifier.width(360.dp).testTag("card")) {
                            ArtifactMessageCard(
                                summary = summary,
                                onOpen = {},
                                onRereadLatest = { polls += it },
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            assertEquals(null, pendingCoverState())
            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS * 2)
            assertEquals(emptyList(), polls, "card that never had a picture never polls")

            // Show a v1 screenshot, then replace with Markdown excerpt: no hold, no poll.
            runOnUiThread {
                summary = cardSummary(id = idA, version = 1, screenshot = ArtifactScreenshot(desktop = "of_d1", phone = "of_p1"))
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals(1, onAllNodesWithTag(ArtifactCardCoverTestTag, useUnmergedTree = true).fetchSemanticsNodes().size)

            runOnUiThread {
                summary = cardSummary(id = idA, version = 2, screenshot = null, excerpt = "# Executive summary")
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals(null, pendingCoverState(), "Markdown excerpt takes over instead of holding an empty box")
            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS * 2)
            assertEquals(emptyList(), polls)

            // Switch to another Artifact id without screenshot: no hold, no poll.
            runOnUiThread {
                summary = cardSummary(id = idB, version = 1, screenshot = null)
                Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeByFrame()
            assertEquals(null, pendingCoverState())
            mainClock.advanceTimeBy(ARTIFACT_CARD_HELD_PICTURE_REREAD_MILLIS * 2)
            assertEquals(emptyList(), polls)
        }
}
