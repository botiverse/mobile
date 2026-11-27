package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSettled
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.thread.ArtifactMessageCard
import build.raft.app.thread.ArtifactSettledTagTestTag
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settled tag on an Artifact's chat card (`ArtifactMessageCard`) and Artifacts home tiles/rows
 * (`ArtifactsHomeTile`, `ArtifactsHomeListRow`; botiverse/slock #15654, #16137): drawn only while
 * the card says it is settled; nothing while it is Working or the card says nothing of Settled. Its
 * words for a screen reader say who settled it, when and at which version, the reader's own as "You".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ArtifactSettledTagUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val working =
        ArtifactSummary(
            id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
            title = "Launch plan",
            description = null,
            kind = ArtifactKind.Markdown,
            version = 3,
            pinned = false,
            latestVersion = 3,
            home = ArtifactHome.Server,
            authorName = "Remy",
            facts = ArtifactFacts(sizeBytes = 100),
        )

    // 2026-11-18T09:30Z read at UTC+8 on 2026-11-21: "11-18 17:30".
    private val settled = ArtifactSettled(ArtifactsHomeActor("agent", "a1", "remy", "Remy"), "2026-11-18T09:30:00.000Z", 3)
    private val timeContext =
        RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 11, 21), nowEpochMillis = 1_795_248_000_000L)

    @Test
    fun theTagIsDrawnOnlyWhileTheCardSaysSettled() = runRobolectricComposeUiTest {
        var summary by mutableStateOf(working)
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Box(Modifier.width(360.dp)) { ArtifactMessageCard(summary, onOpen = {}, readerId = "u1") }
                }
            }
        }
        waitForIdle()
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertDoesNotExist()

        summary = working.copy(settled = settled)
        waitForIdle()
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertContentDescriptionEquals("Settled: Remy settled it on 11-18 17:30 (v3)")

        // The reader's own settle.
        summary = working.copy(settled = settled.copy(by = ArtifactsHomeActor("user", "u1", "kirby", "Kirby")))
        waitForIdle()
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertContentDescriptionEquals("Settled: You settled it on 11-18 17:30 (v3)")

        // Unsettled, or a new version since: Working again, no tag.
        summary = working.copy(version = 4, latestVersion = 4)
        waitForIdle()
        onNodeWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun homeTileAndListRowShowSettledTagOnlyWhenSettled() = runRobolectricComposeUiTest {
        var summary by mutableStateOf(working.copy(settledShown = true))
        val author = ArtifactsHomeActor("agent", "a1", "remy", "Remy")
        setContent {
            val row = ArtifactsHomeRow(summary = summary, author = author, openedAt = null, news = ArtifactsHomeNews())
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(Modifier.width(360.dp)) {
                        ArtifactsHomeTile(
                            row = row,
                            readerId = "u1",
                            serverName = "Northwind Labs",
                            byOpened = false,
                            dimmed = true,
                            settledEnabled = true,
                            settling = false,
                            onOpen = {},
                            onCopyLink = null,
                            onSetSettled = null,
                            cover = { r, _, corner -> ArtifactsHomeTilePlaceholder(r.summary.kind, corner = corner) },
                        )
                        ArtifactsHomeListRow(
                            row = row,
                            readerId = "u1",
                            serverName = "Northwind Labs",
                            byOpened = false,
                            wide = false,
                            dimmed = true,
                            settledEnabled = true,
                            settling = false,
                            onOpen = {},
                            onCopyLink = null,
                            onSetSettled = null,
                            thumbnail = { r, _ -> ArtifactsHomeRowThumbnailPlaceholder(r.summary.kind) },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true).assertCountEquals(0)

        summary = working.copy(settled = settled, settledShown = true)
        waitForIdle()
        val tags = onAllNodesWithTag(ArtifactSettledTagTestTag, useUnmergedTree = true)
        tags.assertCountEquals(2)
        tags[0].assertContentDescriptionEquals("Settled: Remy settled it on 11-18 17:30 (v3)")
        tags[1].assertContentDescriptionEquals("Settled: Remy settled it on 11-18 17:30 (v3)")
    }
}
