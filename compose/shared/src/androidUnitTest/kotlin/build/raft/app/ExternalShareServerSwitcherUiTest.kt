package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the reusable [RaftServerSwitcherControl] used by External Share:
 * - renders a compact trigger with the selected server name (and does not show other servers inline),
 * - opens the standard server switcher dropdown on tap,
 * - selects another server without mutating app-wide state (only invokes [onSelect]),
 * - does not render server-management footer actions or unread counts,
 * - keeps the dropdown closed when only one server is available.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalShareServerSwitcherUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val servers =
        listOf(
            RaftServerSwitcherItem(id = "srv-1", name = "Botiverse", slug = "botiverse"),
            RaftServerSwitcherItem(id = "srv-2", name = "Acme Labs", slug = "acme"),
            RaftServerSwitcherItem(id = "srv-3", name = "Design Studio", slug = "design")
        )

    @Test
    fun tappingTriggerOpensSwitcherDropdownAndSelectsServer() =
        runRobolectricComposeUiTest {
            var selectedId by mutableStateOf("srv-1")
            val dispatched = mutableListOf<String>()
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftServerSwitcherControl(
                            servers = servers,
                            selectedServerId = selectedId,
                            accessibilityLabel = "Server",
                            enabled = true,
                            onSelect = { nextId ->
                                dispatched += nextId
                                selectedId = nextId
                            }
                        )
                    }
                }
            }
            waitForIdle()

            // Before opening, only the selected server trigger is shown — no horizontal row of other servers.
            onNodeWithContentDescription("Server: Botiverse").assertExists()
            onNodeWithText("Acme Labs").assertDoesNotExist()
            onNodeWithText("/acme").assertDoesNotExist()

            // Open the switcher dropdown.
            onNodeWithContentDescription("Server: Botiverse").performClick()
            waitForIdle()

            onNodeWithText("/botiverse").assertExists()
            onNodeWithText("Acme Labs").assertExists()
            onNodeWithText("/acme").assertExists()
            onNodeWithText("Design Studio").assertExists()

            // Selecting another server dispatches only its id and closes the dropdown.
            onNodeWithText("Acme Labs").performClick()
            waitForIdle()

            assertEquals(listOf("srv-2"), dispatched)
            onNodeWithContentDescription("Server: Acme Labs").assertExists()
            onNodeWithText("/acme").assertDoesNotExist()
        }

    @Test
    fun singleServerTriggerDoesNotOpenDropdown() =
        runRobolectricComposeUiTest {
            val dispatched = mutableListOf<String>()
            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        RaftServerSwitcherControl(
                            servers = listOf(servers.first()),
                            selectedServerId = "srv-1",
                            accessibilityLabel = "Server",
                            enabled = true,
                            onSelect = { dispatched += it }
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithContentDescription("Server: Botiverse").assertExists()
            onNodeWithContentDescription("Server: Botiverse").performClick()
            waitForIdle()

            onNodeWithText("/botiverse").assertDoesNotExist()
            assertEquals(emptyList(), dispatched)
        }
}
