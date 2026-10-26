package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement.Badge
import build.raft.app.data.RaftAppLinkCardElement.Stack
import build.raft.app.data.RaftAppLinkCardElement.Text
import build.raft.app.data.RaftAppLinkTextTone
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #proj-mobile task #34: App link item cards ([RaftAppLinkCardView]) at the web's 300dp, light
 * and dark: a Linear issue (a column of a Badge, a default and a muted Text), a nested column
 * with two Badges, a Stamp pull request with only its title (no card body), and a long title
 * that clamps to 3 lines over a long App name and domain. No App icon loads (the generic
 * AppWindow glyph). Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1000dp-xxhdpi")
class RaftAppLinkCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun cardsLight() = capture("thread.app-link-card", RaftAppAppearance.Light) { Cards() }

    @Test
    fun cardsDark() = capture("thread.app-link-card.dark", RaftAppAppearance.Dark) { Cards() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
    }
}

@Composable
private fun FixtureCard(url: String, appName: String, title: String, card: RaftAppLinkCard?) {
    RaftAppLinkCardView(
        card = AppLinkResult.Card(url = url, app = AppLinkApp(appName, null), title = title, card = card),
        onAction = {},
        modifier = Modifier.width(300.dp),
    )
}

@Composable
private fun Cards() {
    FixtureCard(
        url = "https://linear.app/raft/issue/RAFT-812",
        appName = "Linear",
        title = "RAFT-812 Render App link cards natively",
        card =
            RaftAppLinkCard(
                root = "root",
                elements =
                    mapOf(
                        "root" to Stack(listOf("state", "assignee", "updated")),
                        "state" to Badge("In Progress"),
                        "assignee" to Text("Assigned to Niko, due Friday"),
                        "updated" to Text("Updated by Lagoon in Raft Mobile", RaftAppLinkTextTone.Muted),
                    ),
            ),
    )
    FixtureCard(
        url = "https://stamp.build/botiverse/slock/pull/10449",
        appName = "Stamp",
        title = "refactor(i18n): one display-locale source for web and desktop",
        card =
            RaftAppLinkCard(
                root = "root",
                elements =
                    mapOf(
                        "root" to Stack(listOf("id", "states", "summary")),
                        "id" to Text("botiverse/slock#10449", RaftAppLinkTextTone.Muted),
                        "states" to Stack(listOf("merged", "reviewed")),
                        "merged" to Badge("Merged"),
                        "reviewed" to Badge("2 approvals"),
                        "summary" to Text("Martin · +803 −498 across 41 files, with the zh-TW catalog moved to the shared loader."),
                    ),
            ),
    )
    FixtureCard(url = "https://stamp.build/botiverse/mobile/pull/3654", appName = "Stamp", title = "Fix the composer tray (#3654)", card = null)
    FixtureCard(
        url = "https://review.preview.example-apps.build/items/42",
        appName = "An App with a rather long name for its footer",
        title = "A pull request whose title is far too long for one line, so it wraps, and keeps wrapping until the card clamps it at three lines",
        card =
            RaftAppLinkCard(
                root = "summary",
                elements = mapOf("summary" to Text("A muted line long enough to wrap onto a second line in the body.", RaftAppLinkTextTone.Muted)),
            ),
    )
}
