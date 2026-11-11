package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.messageCardMetadataJson
import build.raft.app.data.messageCardOpenBesideStoredJson
import build.raft.app.data.messageCardToRender
import build.raft.app.data.toMessageItemMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card Button with `open: "beside"` (web preview, botiverse/slock #14133; payload of the Server's
 * own test, [messageCardOpenBesideStoredJson]) is drawn as a card, and tapping it opens its page
 * through the row's usual URL handler ([RaftMessageCardView]'s `onOpenUrl`, the thread's
 * `onOpenMarkdownUrl`), exactly as a Button without `open`: the App has no preview pane.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftMessageCardOpenBesideUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aButtonWithOpenBesideIsDrawnAsACardAndOpensThroughTheUsualPath() = runRobolectricComposeUiTest {
        val actionMetadata = messageCardMetadataJson(messageCardOpenBesideStoredJson, 1, "2026-11-11T00:40:00.000Z")
        val metadata = Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), actionMetadata).toMessageItemMetadata()
        val card = requireNotNull(messageCardToRender(metadata, cardsEnabled = true)) { "open: beside must not fall back to the text" }
        val opened = mutableListOf<String>()
        setContent {
            RaftDesignSystemTheme {
                RaftMessageCardView(card, onOpenUrl = { opened += it })
            }
        }
        waitForIdle()

        onNodeWithTag(RaftMessageCardTestTag).assertExists()
        onNodeWithText("Preview of the landing page").assertExists()
        onNodeWithText("Open preview").performClick()
        waitForIdle()
        assertEquals(listOf("https://preview.example.com/"), opened)
    }
}
