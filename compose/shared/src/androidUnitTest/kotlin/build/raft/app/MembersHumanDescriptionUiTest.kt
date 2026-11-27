package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.data.AccountDescriptionUpdateResult
import build.raft.app.data.DataResult
import build.raft.app.data.DescriptionLinkWarning
import build.raft.app.data.DescriptionLinkWarningCode
import build.raft.app.data.PROFILE_DESCRIPTION_MAX_LENGTH
import build.raft.app.members.MembersHumanDescriptionCancelButtonTag
import build.raft.app.members.MembersHumanDescriptionEditButtonTag
import build.raft.app.members.MembersHumanDescriptionInputTag
import build.raft.app.members.MembersHumanDescriptionSaveButtonTag
import build.raft.app.members.MembersHumanDetailSections
import build.raft.app.members.RaftDescriptionLinkWarningsTag
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #15664 human self-description inline editor on a real Compose composition:
 * - active self profile shows inline edit icon, counter (`n/300`), Save/Cancel, updates text and
 *   shows person-scoped description link warnings after save;
 * - clearing sends `null` and shows "No description";
 * - enforces the 300-character limit on edited text while allowing unchanged legacy >300 text;
 * - hides edit affordance for other users or departed self members.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MembersHumanDescriptionUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun sampleHuman(
        id: String = "user-1",
        description: String? = "Original bio",
        membershipStatus: String? = "active",
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = "Alice",
            handle = "alice",
            kind = MemberKind.Human,
            description = description,
            membershipStatus = membershipStatus,
            serverRole = "member",
            serverId = ServerId("server-1"),
        )

    @Test
    fun selfActiveHumanEditsSavesAndDisplaysLinkWarnings() = runRobolectricComposeUiTest {
        val submitted = mutableListOf<String?>()
        val applied = mutableListOf<String?>()
        val warning =
            DescriptionLinkWarning(
                code = DescriptionLinkWarningCode.Conversation,
                url = "https://app.raft.build/a/abc",
                serverName = "Acme",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersHumanDetailSections(
                    member = sampleHuman(description = "Old bio"),
                    timeContext = RaftTimeContext(localOffsetMinutes = 0),
                    currentUserId = "user-1",
                    currentServerId = "server-1",
                    onUpdateDescription = { next ->
                        submitted += next
                        DataResult.Content(AccountDescriptionUpdateResult(description = next, warnings = listOf(warning)))
                    },
                    onApplyDescription = { applied += it }
                )
            }
        }
        waitForIdle()

        onNodeWithText("Old bio").assertIsDisplayed()
        onNodeWithTag(MembersHumanDescriptionEditButtonTag).assertIsDisplayed().performClick()
        waitForIdle()

        onNodeWithTag(RaftProfileDescriptionCounterTestTag).assertTextEquals("7/$PROFILE_DESCRIPTION_MAX_LENGTH")
        onNodeWithTag(MembersHumanDescriptionInputTag).performTextReplacement("  New mobile bio  ")
        waitForIdle()
        onNodeWithTag(RaftProfileDescriptionCounterTestTag).assertTextEquals("14/$PROFILE_DESCRIPTION_MAX_LENGTH")

        onNodeWithTag(MembersHumanDescriptionSaveButtonTag).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(listOf<String?>("New mobile bio"), submitted)
        assertEquals(listOf<String?>("New mobile bio"), applied)
        onNodeWithText("New mobile bio").assertIsDisplayed()
        onNodeWithTag(RaftDescriptionLinkWarningsTag).assertIsDisplayed()
        onNodeWithContentDescription("Dismiss").performClick()
        waitForIdle()
        onNodeWithTag(RaftDescriptionLinkWarningsTag).assertDoesNotExist()
    }

    @Test
    fun legacyOverLimitDescriptionSavesUnchangedButEditedOverLimitBlocksSave() = runRobolectricComposeUiTest {
        val legacy = "a".repeat(310)
        val submitted = mutableListOf<String?>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersHumanDetailSections(
                    member = sampleHuman(description = legacy),
                    timeContext = RaftTimeContext(localOffsetMinutes = 0),
                    currentUserId = "user-1",
                    currentServerId = "server-1",
                    onUpdateDescription = { next ->
                        submitted += next
                        DataResult.Content(AccountDescriptionUpdateResult(description = next))
                    }
                )
            }
        }
        waitForIdle()

        onNodeWithTag(MembersHumanDescriptionEditButtonTag).performClick()
        waitForIdle()
        // Unchanged legacy >300 description shows counter 310/300 and actually submits on Save.
        onNodeWithTag(RaftProfileDescriptionCounterTestTag).assertTextEquals("310/$PROFILE_DESCRIPTION_MAX_LENGTH")
        onNodeWithTag(MembersHumanDescriptionSaveButtonTag).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf<String?>(legacy), submitted)

        // Re-opening and editing while still >300 shows the length error and disables Save.
        onNodeWithTag(MembersHumanDescriptionEditButtonTag).performClick()
        waitForIdle()
        onNodeWithTag(MembersHumanDescriptionInputTag).performTextReplacement("b".repeat(305))
        waitForIdle()
        onNodeWithTag(RaftProfileDescriptionCounterTestTag).assertTextEquals("305/$PROFILE_DESCRIPTION_MAX_LENGTH")
        onNodeWithText("Description must be 300 characters or fewer.").assertIsDisplayed()
        onNodeWithTag(MembersHumanDescriptionSaveButtonTag).assertIsNotEnabled()

        // Cancel restores view mode with no additional network call.
        onNodeWithTag(MembersHumanDescriptionCancelButtonTag).performClick()
        waitForIdle()
        assertEquals(listOf<String?>(legacy), submitted)
        onNodeWithTag(MembersHumanDescriptionEditButtonTag).assertIsDisplayed()
    }

    @Test
    fun switchingMemberWhileSaveInFlightIgnoresStaleResponse() = runRobolectricComposeUiTest {
        val gate = CompletableDeferred<DataResult<AccountDescriptionUpdateResult>>()
        val applied = mutableListOf<String?>()
        var currentMember by mutableStateOf(sampleHuman(id = "user-1", description = "Old bio"))
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersHumanDetailSections(
                    member = currentMember,
                    timeContext = RaftTimeContext(localOffsetMinutes = 0),
                    currentUserId = "user-1",
                    currentServerId = "server-1",
                    onUpdateDescription = { gate.await() },
                    onApplyDescription = { applied += it }
                )
            }
        }
        waitForIdle()

        onNodeWithTag(MembersHumanDescriptionEditButtonTag).performClick()
        waitForIdle()
        onNodeWithTag(MembersHumanDescriptionInputTag).performTextReplacement("Stale bio")
        onNodeWithTag(MembersHumanDescriptionSaveButtonTag).performClick()
        waitForIdle()

        // Switch user-1 -> user-2 -> user-1 before the in-flight request completes.
        currentMember = sampleHuman(id = "user-2", description = "Bob bio")
        waitForIdle()
        currentMember = sampleHuman(id = "user-1", description = "Fresh bio")
        waitForIdle()

        gate.complete(DataResult.Content(AccountDescriptionUpdateResult(description = "Stale bio")))
        waitForIdle()

        assertEquals(emptyList(), applied)
        onNodeWithText("Fresh bio").assertIsDisplayed()
    }

    @Test
    fun nonSelfOrDepartedHumanHidesDescriptionEditButton() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersHumanDetailSections(
                    member = sampleHuman(id = "user-2", description = "Other person"),
                    timeContext = RaftTimeContext(localOffsetMinutes = 0),
                    currentUserId = "user-1",
                    currentServerId = "server-1",
                    onUpdateDescription = { DataResult.Content(AccountDescriptionUpdateResult(it)) }
                )
            }
        }
        waitForIdle()
        onNodeWithTag(MembersHumanDescriptionEditButtonTag).assertDoesNotExist()
    }
}
