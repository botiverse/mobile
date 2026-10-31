package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactFacts
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import java.time.Instant
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Artifact cards under a message (web `ArtifactMessageRefs.tsx` / `ArtifactMessageCard.tsx`,
 * botiverse/slock staging 84e41d9be), light and dark, on a fixed clock:
 *  1. an HTML card: the code glyph with its HTML badge, title and v4, the description (2 lines: HTML
 *     has no fact line), ↗, and the footer: `# proj-workspace`, "Lagoon updated 2 hours ago", 3 comments;
 *  2. a PDF pinned at v2 of 5: "Latest is v5 · 6 pages", the description cut at one line, the whole
 *     server, no comments;
 *  3. a deleted one: gray and dimmed, its title and "Deleted by Martin 1 day ago".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
class ThreadArtifactMessageCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun artifactMessageCardsLight() = capture("thread.artifact-message-cards", RaftAppAppearance.Light)

    @Test
    fun artifactMessageCardsDark() = capture("thread.artifact-message-cards.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val now = 1_792_635_000_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 10, 22), nowEpochMillis = now)
        val ago = { millis: Long -> Instant.ofEpochMilli(now - millis).toString() }
        val html =
            ArtifactSummary(
                id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11",
                title = "Q3 revenue report",
                description = "Revenue by region with the October forecast and the open questions for finance.",
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Conversation("proj-workspace", dm = false),
                authorName = "Lagoon",
                updatedAt = ago(2 * 60 * 60 * 1000L),
                commentCount = 3,
                facts = ArtifactFacts(sizeBytes = 24_000),
            )
        val pdf =
            ArtifactSummary(
                id = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f",
                title = "Onboarding checklist",
                description = "Everything a new teammate needs in the first week, from accounts to the first review.",
                kind = ArtifactKind.Pdf,
                version = 2,
                pinned = true,
                latestVersion = 5,
                home = ArtifactHome.Server,
                authorName = "Niko",
                updatedAt = ago(3 * 24 * 60 * 60 * 1000L),
                facts = ArtifactFacts(sizeBytes = 400_000, pages = 6),
            )
        val view =
            ThreadArtifactMessageRefsView.Cards(
                listOf(
                    ThreadArtifactMessageRef.Card("a", ArtifactLinkTarget(html.id), html),
                    ThreadArtifactMessageRef.Card("b", ArtifactLinkTarget(pdf.id, 2), pdf),
                    ThreadArtifactMessageRef.Deleted("c", ArtifactDeletedShown("Launch plan draft", "Martin", ago(26 * 60 * 60 * 1000L))),
                ),
            )
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        ThreadArtifactMessageRefsContent(view = view, onOpen = {}, onOpenInNewPage = {})
                    }
                }
            }
        }
    }
}
