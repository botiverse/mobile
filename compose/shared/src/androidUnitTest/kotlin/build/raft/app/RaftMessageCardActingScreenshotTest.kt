package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.MessageCardActingApp
import build.raft.app.data.MessageCardActingPage
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.messageCardActingMetadataJson
import build.raft.app.data.messageCardReadBody
import build.raft.app.data.readMessageCardFromMetadata
import build.raft.app.data.parseMessageCardReadAnswer
import build.raft.app.data.toMessageItemMetadata
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RFC 086 App-sent cards ([RaftMessageCardView] with acting buttons; the `acting_buttons_as_stored`
 * card of botiverse/slock #14723) on a phone, light and dark, for three viewers:
 *  1. the person the card is for: Approve and Deny enabled (the App's own buttons, no ↗);
 *  2. anyone else: both disabled, "Waiting for Alice" under them;
 *  3. a viewer who turned the App off: both disabled, saying so and how to change it.
 * None shows the reason stored for older clients ("Update Raft to use this button").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xxhdpi")
class RaftMessageCardActingScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun actingCardsLight() = capture("thread.message-card.acting", RaftAppAppearance.Light)

    @Test
    fun actingCardsDark() = capture("thread.message-card.acting.dark", RaftAppAppearance.Dark)

    private val card =
        requireNotNull(
            readMessageCardFromMetadata(
                Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), messageCardActingMetadataJson()).toMessageItemMetadata()
            )
        )

    /** Buttons that can be pressed but go nowhere: the fixture draws the pressable state. */
    private val noActions =
        ThreadAppLinkCardActionHost(
            prepare = { _, _ -> AppLinkActionResult.Failed(null, null, outOfDate = false) },
            execute = { _, _, _ -> AppLinkActionResult.Failed(null, null, outOfDate = false) },
            reread = { true },
            toast = {},
        )

    private fun live(messageCard: String) = requireNotNull(parseMessageCardReadAnswer(messageCardReadBody(messageCard)))

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val forViewer =
            live(
                """{"card_revision":"1","app":$MessageCardActingApp,"actions":[""" +
                    """{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t","handle":"h-approve"},""" +
                    """{"element_id":"deny","action_id":"deny","url":"$MessageCardActingPage","ticket":"t","handle":"h-deny"}]}""",
            )
        val waiting = live("""{"card_revision":"1","actions":[],"waiting_for":{"id":"user-alice","name":"Alice"}}""")
        val declined = live("""{"card_revision":"1","actions":[],"consent":"declined","app":$MessageCardActingApp}""")
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(
                    Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (live in listOf(forViewer, waiting, declined)) {
                        RaftMessageCardView(card, onOpenUrl = {}, modifier = Modifier.fillMaxWidth(), actingLive = live, actingActions = noActions)
                    }
                }
            }
        }
    }
}
