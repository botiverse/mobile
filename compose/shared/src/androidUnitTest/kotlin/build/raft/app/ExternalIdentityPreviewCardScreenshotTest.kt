package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import build.raft.app.model.ExternalMessageAuthor
import build.raft.designsystem.components.RaftPreviewCardSurface
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hover card on a Slack-bridged author's avatar (web `ExternalIdentityPreviewBody`), light and
 * dark: a Slack person from a known workspace, and an App with no picture and no workspace (the
 * cyan link sticker, "Slack · App"). Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h480dp-xxhdpi")
class ExternalIdentityPreviewCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun light() = capture("external-identity-preview", RaftAppAppearance.Light)

    @Test fun dark() = capture("external-identity-preview.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Card(person)
                        Card(app)
                    }
                }
            }
        }
}

@Composable
private fun Card(author: ExternalMessageAuthor) {
    RaftPreviewCardSurface { RaftExternalIdentityPreviewCardContent(author, shownName = author.displayName) }
}

private val person =
    ExternalMessageAuthor(displayName = "Grace Hopper", provider = "slack", actorKind = "human", workspaceName = "Acme Robotics")

private val app = ExternalMessageAuthor(displayName = "Deploy Bot", provider = "slack", actorKind = "bot")
