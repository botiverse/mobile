package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.messageCardBuildStoredJson
import build.raft.app.data.messageCardMetadataJson
import build.raft.app.data.messageCardToRender
import build.raft.app.data.toMessageItemMetadata
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RFC 082 card messages ([RaftMessageCardView]) on a phone, light and dark, on a fixed clock:
 *  1. Hands' TestFlight build card (botiverse/slock 1e9266662 `messageCard.test.ts` `buildCard`),
 *     replaced twice (revision 3): the title, the details row (the state chip, drawn neutral, then
 *     "Internal, Botiverse", the time and the duration apart by "·"), the link with its domain and
 *     ↗, and the footer "Updated 7 minutes ago".
 *  2. A first revision (no Updated line) with a ghost link and a link the sender disabled, whose
 *     reason is the caption under them (no ↗ on it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
class RaftMessageCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun messageCardsLight() = capture("thread.message-card", RaftAppAppearance.Light)

    @Test
    fun messageCardsDark() = capture("thread.message-card.dark", RaftAppAppearance.Dark)

    private fun card(actionMetadata: String) =
        requireNotNull(
            messageCardToRender(
                Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), actionMetadata).toMessageItemMetadata(),
                cardsEnabled = true,
            )
        )

    private fun capture(name: String, appearance: RaftAppAppearance) {
        // 2026-11-03 12:17:00Z.
        val now = 1_793_708_220_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 11, 3), nowEpochMillis = now)
        val build = card(messageCardMetadataJson(messageCardBuildStoredJson, 3, "2026-11-03T12:10:00.000Z"))
        val links =
            card(
                messageCardMetadataJson(
                    """{"version":1,"root":"root","elements":{""" +
                        """"root":{"type":"Stack","props":{},"children":["title","note","docs","release"]},""" +
                        """"title":{"type":"Text","props":{"text":"Deploy 2026.11.03-1 to staging"}},""" +
                        """"note":{"type":"Text","props":{"text":"a1b2c3d","mono":true}},""" +
                        """"docs":{"type":"Button","props":{"label":"Release notes","variant":"ghost","action":{"kind":"navigate","url":"https://docs.example.com/releases/2026-11-03"}}},""" +
                        """"release":{"type":"Button","props":{"label":"Promote","action":{"kind":"navigate","url":"https://deploy.example.com/promote"},"disabledReason":"Waiting for the smoke test"}}""" +
                        """}}""",
                    1,
                    "2026-11-03T12:16:00.000Z",
                )
            )
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RaftMessageCardView(build, onOpenUrl = {}, modifier = Modifier.fillMaxWidth())
                        RaftMessageCardView(links, onOpenUrl = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
