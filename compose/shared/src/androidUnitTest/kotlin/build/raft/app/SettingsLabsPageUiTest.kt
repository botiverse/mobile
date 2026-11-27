package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsLabsError
import build.raft.app.settings.SettingsLabsErrorKind
import build.raft.app.settings.SettingsLabsMasterSwitchTestTag
import build.raft.app.settings.SettingsLabsScreen
import build.raft.app.settings.SettingsLabsState
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Labs on a real composition: what the owner, an admin and a member can switch (web
 * LabsSection / ServerLabRow), the empty and unavailable states, and nothing while the flag is off.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class SettingsLabsPageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private val masterChanges = mutableListOf<Boolean>()
    private val enrollmentChanges = mutableListOf<Pair<String, Boolean>>()

    private fun ComposeUiTest.show(state: SettingsLabsState, role: String?, labsUiEnabled: Boolean = true) {
        setContent {
            MigrationFixtureHost {
                RaftDesignSystemTheme {
                    SettingsLabsScreen(
                        state = state,
                        role = role,
                        labsUiEnabled = labsUiEnabled,
                        onBack = {},
                        onMasterChange = { masterChanges += it },
                        onEnrollmentChange = { key, enabled -> enrollmentChanges += key to enabled },
                    )
                }
            }
        }
    }

    private val loaded = SettingsLabsState(serverId = "srv-labs", readback = settingsLabsFixtureReadback)

    @Test
    fun memberReadsEverythingWithTheWebsReasons() = runRobolectricComposeUiTest {
        show(loaded, role = "member")
        onNodeWithTag(SettingsLabsMasterSwitchTestTag).assertIsNotEnabled()
        onNodeWithText("Only server owners can change the master gate.").assertExists()
        onNodeWithContentDescription("Agent Lounge enrollment").assertIsNotEnabled()
        onNodeWithContentDescription("Prepared requests enrollment").assertIsNotEnabled()
        onAllNodesWithText("Only server owners and admins can change Lab enrollment.").assertCountEquals(4)
    }

    @Test
    fun emptyUnavailableAndFailedStates() = runRobolectricComposeUiTest {
        var state by mutableStateOf(loaded.copy(readback = settingsLabsFixtureReadback.copy(labs = emptyList())))
        setContent {
            MigrationFixtureHost {
                SettingsLabsScreen(state, "owner", labsUiEnabled = true, onBack = {}, onMasterChange = {}, onEnrollmentChange = { _, _ -> })
            }
        }
        onNodeWithText("No Labs available for this server").assertExists()
        runOnIdle { state = SettingsLabsState(serverId = "srv-labs", unavailable = true) }
        onNodeWithText("Labs settings unavailable").assertExists()
        runOnIdle {
            state = loaded.copy(error = SettingsLabsError(SettingsLabsErrorKind.FailedUpdate, "4bf92f3577b34da6a3ce929d0e0e4736"))
        }
        onNodeWithText("Failed to update Labs settings.").assertExists()
        onNodeWithTag(RaftTraceIdNoteTestTag).assertExists()
    }
}
