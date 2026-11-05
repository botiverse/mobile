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
 * RFC 082 v2.0 card messages ([RaftMessageCardView]) on a phone, light and dark, on a fixed clock:
 *  1. The shared vector's `header_full` (botiverse/slock #13224): the header (title, subtitle,
 *     three tags in their tones), Fields (label above value on a phone, the version in the code
 *     face), a Divider, Markdown (bold, a list, inline code) and the link.
 *  2. Markdown outside the subset drawn as its source (a heading, an HTML tag), `@name` and
 *     `#channel` as plain text, and a row whose Markdown stands on its own line between the
 *     badge's line and the text after it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class RaftMessageCardV2ScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun messageCardsV2Light() = capture("thread.message-card-v2", RaftAppAppearance.Light)

    @Test
    fun messageCardsV2Dark() = capture("thread.message-card-v2.dark", RaftAppAppearance.Dark)

    private fun card(cardJson: String) =
        requireNotNull(
            messageCardToRender(
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(WireActionCardMetadata.serializer(), messageCardMetadataJson(cardJson, 1, "2026-11-05T09:12:00.000Z"))
                    .toMessageItemMetadata(),
                cardsEnabled = true,
            )
        )

    private fun capture(name: String, appearance: RaftAppAppearance) {
        // 2026-11-05 09:15:00Z.
        val now = 1_793_870_100_000L
        val timeContext = RaftTimeContext(localOffsetMinutes = 8 * 60, todayEpochDay = raftEpochDayForDate(2026, 11, 5), nowEpochMillis = now)
        val play =
            card(
                """{"version":1,"header":{"title":"Play rejected this submission: 1.20.0","subtitle":"Raft Android · Google Play · production",""" +
                    """"tags":[{"text":"Failed","tone":"danger"},{"text":"Policy","tone":"warning"},{"text":"Android","tone":"neutral"}]},""" +
                    """"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["facts","rule","notes","open"]},""" +
                    """"facts":{"type":"Fields","props":{"items":[{"label":"Version","value":"1.20.0 (12000003)","mono":true},{"label":"Track","value":"production"},{"label":"Reviewed","value":"2026-11-05 09:12 UTC"}]}},""" +
                    """"rule":{"type":"Divider","props":{}},""" +
                    """"notes":{"type":"Markdown","props":{"content":"Google says the **target API level** is too low.\n\n- raise `targetSdk` to 35\n- send it again for review"}},""" +
                    """"open":{"type":"Button","props":{"label":"Open in Play Console","action":{"kind":"navigate","url":"https://play.google.com/console/"}}}}}""",
            )
        val source =
            card(
                """{"version":1,"header":{"title":"Nightly build","tags":[{"text":"Passed","tone":"success"},{"text":"Info","tone":"info"}]},""" +
                    """"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["notes","row"]},""" +
                    """"notes":{"type":"Markdown","props":{"content":"# Not a heading\n\nAsk @artin in #builds about <b>this</b>."}},""" +
                    """"row":{"type":"Stack","props":{"direction":"row"},"children":["state","md","after"]},""" +
                    """"state":{"type":"Badge","props":{"text":"Ready"}},""" +
                    """"md":{"type":"Markdown","props":{"content":"*on its own line*"}},""" +
                    """"after":{"type":"Text","props":{"text":"then the row again","tone":"muted"}}}}""",
            )
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RaftMessageCardView(play, onOpenUrl = {}, modifier = Modifier.fillMaxWidth())
                        RaftMessageCardView(source, onOpenUrl = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
