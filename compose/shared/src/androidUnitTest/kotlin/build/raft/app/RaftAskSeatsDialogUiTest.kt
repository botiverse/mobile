package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SEAT_REQUEST_REASON_MAX_LENGTH
import build.raft.app.data.SeatJoinSeatUsage
import build.raft.app.data.SeatRequestPurpose
import build.raft.app.resources.StringDesc
import build.raft.app.settings.ASK_SEATS_ERROR_BANNER_TAG
import build.raft.app.settings.ASK_SEATS_OWNER_PICKER_TAG
import build.raft.app.settings.ASK_SEATS_OWNER_ROW_TAG
import build.raft.app.settings.ASK_SEATS_REASON_FIELD_TAG
import build.raft.app.settings.ASK_SEATS_SEND_BUTTON_TAG
import build.raft.app.settings.AskSeatsOwner
import build.raft.app.settings.AskSeatsOwnerState
import build.raft.app.settings.RaftAskSeatsDialog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h780dp-xxhdpi")
class RaftAskSeatsDialogUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun englishLocale() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun singleOwnerFlowSupportsReasonEntryAndSend() =
        runRobolectricComposeUiTest {
            var reason by mutableStateOf("")
            var sentCount = 0

            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        RaftAskSeatsDialog(
                            serverId = "srv-1",
                            serverName = "Acme",
                            purpose = SeatRequestPurpose.Join(userId = "u-grace", name = "Grace Hopper"),
                            usage = SeatJoinSeatUsage(seats = 5, humans = 5, agents = 0),
                            ownerState =
                                AskSeatsOwnerState.Loaded(
                                    owners = listOf(AskSeatsOwner("u-ada", "Ada Lovelace", isCreator = true)),
                                    selectedOwnerId = "u-ada",
                                ),
                            reason = reason,
                            onReasonChange = { reason = it },
                            onSelectOwner = {},
                            onRetryOwners = {},
                            sending = false,
                            errorMessage = null,
                            onSend = { sentCount += 1 },
                            onDismiss = {},
                        )
                    }
                }
            }

            onNode(hasText("Grace Hopper needs a seat to join")).assertExists()
            onNode(hasText("All 5 seats on Acme are in use, and only an owner can add seats.")).assertExists()
            onNode(hasTestTag(ASK_SEATS_OWNER_ROW_TAG)).assertExists()
            onNode(hasText("Ada Lovelace")).assertExists()
            onNode(hasText("Owner")).assertExists()
            onNode(hasTestTag(ASK_SEATS_SEND_BUTTON_TAG)).assertIsEnabled()
            onNode(hasText("Send to Ada Lovelace")).assertExists()

            onNode(hasTestTag(ASK_SEATS_REASON_FIELD_TAG)).performTextInput("Joining iOS release team")
            waitForIdle()
            assertEquals("Joining iOS release team", reason)

            onNode(hasTestTag(ASK_SEATS_SEND_BUTTON_TAG)).performClick()
            waitForIdle()
            assertEquals(1, sentCount)
        }

    @Test
    fun multipleOwnersPickerAndHiddenRosterAndFailureStatesBehaveCorrectly() =
        runRobolectricComposeUiTest {
            val owners =
                listOf(
                    AskSeatsOwner("u-ada", "Ada Lovelace", isCreator = true),
                    AskSeatsOwner("u-lin", "Linus", isCreator = false),
                )
            var ownerState by mutableStateOf<AskSeatsOwnerState>(
                AskSeatsOwnerState.Loaded(owners = owners, selectedOwnerId = "u-ada")
            )
            var reason by mutableStateOf("")
            var errorMessage by mutableStateOf<String?>(null)
            var retryCount = 0

            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        RaftAskSeatsDialog(
                            serverId = "srv-1",
                            serverName = "Acme",
                            purpose = SeatRequestPurpose.AgentCreate("scout"),
                            ownerState = ownerState,
                            reason = reason,
                            onReasonChange = { reason = it },
                            onSelectOwner = { picked ->
                                ownerState = AskSeatsOwnerState.Loaded(owners = owners, selectedOwnerId = picked)
                            },
                            onRetryOwners = { retryCount += 1 },
                            sending = false,
                            errorMessage = errorMessage,
                            onSend = {},
                            onDismiss = {},
                        )
                    }
                }
            }

            onNode(hasText("Add seats to Acme")).assertExists()
            onNode(hasTestTag(ASK_SEATS_OWNER_PICKER_TAG)).assertExists()
            onNode(hasText("Send to (2 owners)")).assertExists()
            onNode(hasText("Send to Ada Lovelace")).assertExists()

            // Pick second owner via radio option -> CTA updates.
            onNode(hasText("Linus")).performClick()
            waitForIdle()
            onNode(hasText("Send to Linus")).assertExists()

            // Overlong reason disables Send button.
            runOnIdle { reason = "a".repeat(SEAT_REQUEST_REASON_MAX_LENGTH + 1) }
            waitForIdle()
            onNode(hasTestTag(ASK_SEATS_SEND_BUTTON_TAG)).assertIsNotEnabled()

            // Hidden roster hides owner picker and uses "Send to an owner".
            runOnIdle {
                reason = ""
                ownerState = AskSeatsOwnerState.ServerChosen
                errorMessage = "You have too many requests waiting for an owner."
            }
            waitForIdle()
            onNode(hasTestTag(ASK_SEATS_OWNER_PICKER_TAG)).assertDoesNotExist()
            onNode(hasTestTag(ASK_SEATS_ERROR_BANNER_TAG)).assertExists()
            onNode(hasText("Send to an owner")).assertExists()
            onNode(hasTestTag(ASK_SEATS_SEND_BUTTON_TAG)).assertIsEnabled()

            // Failed owner load shows retry banner and hides Send button.
            runOnIdle {
                errorMessage = null
                ownerState = AskSeatsOwnerState.Failed
            }
            waitForIdle()
            onNode(hasText("The server's owners couldn't be loaded.")).assertExists()
            onNode(hasTestTag(ASK_SEATS_SEND_BUTTON_TAG)).assertDoesNotExist()
            onNode(hasText("Retry")).performClick()
            waitForIdle()
            assertEquals(1, retryCount)
        }
}
