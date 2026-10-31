package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIMEZONE
import build.raft.app.data.LANGUAGE_REGION_ACTION_TIME_FORMAT
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
import build.raft.designsystem.components.RaftSaveStatusState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberSettingsPickSaveStatus] on a real composition, driven the way the settings store drives
 * a Language & Region pick: the pick, the store's in-flight action state, then its result. "Saved"
 * shows once the pick is stored and clears after two seconds; a failure says what failed (and the
 * control is back on the stored value, [settingsLanguageRegionShownValue]) until the next pick.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsPickSaveStatusUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var pick by mutableStateOf<SettingsLanguageRegionPick?>(null, referentialEqualityPolicy())
    private var actionState by mutableStateOf<SharedSettingsActionState?>(null, referentialEqualityPolicy())
    private var status: RaftSaveStatusState = RaftSaveStatusState.Idle

    private fun ComposeUiTest.show(action: String) {
        mainClock.autoAdvance = false
        setContent {
            status = rememberSettingsPickSaveStatus(action, pick, actionState, FailureMessage)
        }
        settle()
    }

    /** Frames for the pick or result to reach the effect, then for the line it sets to recompose. */
    private fun ComposeUiTest.settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    private fun ComposeUiTest.pickAndRun(action: String, value: String) {
        pick = SettingsLanguageRegionPick(action, value, actionState)
        settle()
        actionState = SharedSettingsActionState(action, inProgress = true)
        settle()
    }

    @Test
    fun timeFormatSavesThenClearsAfterTwoSeconds() = runRobolectricComposeUiTest {
        show(LANGUAGE_REGION_ACTION_TIME_FORMAT)
        assertEquals(RaftSaveStatusState.Idle, status)

        pickAndRun(LANGUAGE_REGION_ACTION_TIME_FORMAT, "12h")
        assertEquals(RaftSaveStatusState.Idle, status, "nothing while saving: the control already shows the pick")

        actionState = SharedSettingsActionState(LANGUAGE_REGION_ACTION_TIME_FORMAT)
        settle()
        assertEquals(RaftSaveStatusState.Saved, status)

        mainClock.advanceTimeBy(2_100)
        waitForIdle()
        assertEquals(RaftSaveStatusState.Idle, status)
    }

    @Test
    fun timezoneFailureSaysSoUntilTheNextPick() = runRobolectricComposeUiTest {
        show(LANGUAGE_REGION_ACTION_TIMEZONE)

        pickAndRun(LANGUAGE_REGION_ACTION_TIMEZONE, "Asia/Tokyo")
        val failed = SharedSettingsActionState(LANGUAGE_REGION_ACTION_TIMEZONE, errorCode = "network", screenError = ScreenError("network"))
        actionState = failed
        settle()
        assertEquals(RaftSaveStatusState.Failed(FailureMessage), status)
        assertEquals("UTC", settingsLanguageRegionShownValue("UTC", LANGUAGE_REGION_ACTION_TIMEZONE, pick, actionState))

        // A failure stays (it doesn't time out) ...
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        assertEquals(RaftSaveStatusState.Failed(FailureMessage), status)

        // ... until the next pick, which the server refuses with its own words.
        pickAndRun(LANGUAGE_REGION_ACTION_TIMEZONE, "Asia/Tokyo")
        assertEquals(RaftSaveStatusState.Idle, status)
        actionState =
            SharedSettingsActionState(
                LANGUAGE_REGION_ACTION_TIMEZONE,
                errorCode = "bad_request",
                screenError = ScreenError("bad_request", serverMessage = "Unknown timezone")
            )
        settle()
        assertEquals(RaftSaveStatusState.Failed("Unknown timezone"), status)
    }

    @Test
    fun appLanguageReportsOnlyItsOwnPick() = runRobolectricComposeUiTest {
        // A result already on screen when the page opens is not a save made here.
        actionState = SharedSettingsActionState(MOBILE_ACTION_LANGUAGE)
        show(MOBILE_ACTION_LANGUAGE)
        assertEquals(RaftSaveStatusState.Idle, status)

        // Another control's pick leaves this line alone.
        pickAndRun(LANGUAGE_REGION_ACTION_TIME_FORMAT, "12h")
        actionState = SharedSettingsActionState(LANGUAGE_REGION_ACTION_TIME_FORMAT)
        settle()
        assertEquals(RaftSaveStatusState.Idle, status)

        pickAndRun(MOBILE_ACTION_LANGUAGE, "ja")
        actionState = SharedSettingsActionState(MOBILE_ACTION_LANGUAGE)
        settle()
        assertEquals(RaftSaveStatusState.Saved, status)
    }

    private companion object {
        const val FailureMessage = "Failed to update date and time preferences"
    }
}
