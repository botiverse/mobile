package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ComputerAgentOwner
import build.raft.app.data.ComputerDeleteMachineResult
import build.raft.app.data.ComputerNotifiedOwnerEntry
import build.raft.app.data.ComputerNotifyAgentOwnersResult
import build.raft.app.data.ComputerNotifySkipReason
import build.raft.app.data.ComputerOthersAgent
import build.raft.app.data.ComputerOthersAgentsRepository
import build.raft.app.data.ComputerRevokeAndDeleteResult
import build.raft.app.data.ComputerSkippedOwnerEntry
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.settings_computers_force_unbind_title_one
import build.raft.app.resources.settings_computers_force_unbind_title_other
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI tests for #wg-mobile-sync:#63 (botiverse/slock #12882 / #14942 / #15305):
 * Delete Computer on one's own Computer lists other people's agents, supports per-agent
 * "Notify owner to move it" (notified, rate-limited, no-owner, failure/undelivered), and
 * "Force unbind and delete" with conflict retry and revoked-not-removed fallback copy.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class DeleteComputerOthersAgentsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val fixedNowMs = 1_795_680_000_000L // 2026-11-26T08:00:00Z
    private val fixedTimeContext =
        RaftTimeContext(
            nowEpochMillis = fixedNowMs,
            localOffsetMinutes = 0,
        )

    private class FakeOthersAgentsRepository(
        var onRevokeAndDelete: (forceAgentIds: List<String>) -> ComputerRevokeAndDeleteResult = {
            ComputerRevokeAndDeleteResult.Deleted
        },
        var onDeleteMachine: () -> ComputerDeleteMachineResult = {
            ComputerDeleteMachineResult.Deleted
        },
        var onNotify: (agentIds: List<String>) -> DataResult<ComputerNotifyAgentOwnersResult> = { ids ->
            DataResult.Content(
                ComputerNotifyAgentOwnersResult(
                    notified = ids.map { ComputerNotifiedOwnerEntry(it, "2026-11-26T07:59:00.000Z") }
                )
            )
        },
    ) : ComputerOthersAgentsRepository {
        val revokeForceCalls = mutableListOf<List<String>>()
        val notifyCalls = mutableListOf<List<String>>()
        var deleteMachineCalls = 0

        override suspend fun revokeKeyAndDeleteComputer(
            serverId: ServerId,
            machineId: String,
            forceAgentIds: List<String>,
        ): ComputerRevokeAndDeleteResult {
            revokeForceCalls += forceAgentIds
            return onRevokeAndDelete(forceAgentIds)
        }

        override suspend fun deleteMachine(serverId: ServerId, machineId: String): ComputerDeleteMachineResult {
            deleteMachineCalls += 1
            return onDeleteMachine()
        }

        override suspend fun notifyAgentOwners(
            serverId: ServerId,
            machineId: String,
            agentIds: List<String>,
        ): DataResult<ComputerNotifyAgentOwnersResult> {
            notifyCalls += agentIds
            return onNotify(agentIds)
        }
    }

    @Test
    fun ownComputerWithOnlyOthersAgentsOpensOthersDialogNotifiesAndForceUnbinds() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val aliceAgent =
            ComputerOthersAgent(
                id = "a-alice",
                name = "Scout",
                owner = ComputerAgentOwner("u-alice", "Alice"),
                ownerNotifiedAt = null,
                ownerNextNotifyAt = null,
                personalAssistant = false,
            )
        val ownerlessAssistant =
            ComputerOthersAgent(
                id = "a-pa",
                name = "Orphan Assistant",
                owner = null,
                ownerNotifiedAt = null,
                ownerNextNotifyAt = null,
                personalAssistant = true,
            )
        val repo =
            FakeOthersAgentsRepository(
                onRevokeAndDelete = { forceIds ->
                    if (forceIds.isEmpty()) {
                        ComputerRevokeAndDeleteResult.OthersAgents(listOf(aliceAgent, ownerlessAssistant))
                    } else {
                        ComputerRevokeAndDeleteResult.Deleted
                    }
                },
            )
        var deleted = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftComputerDeleteFlowDialog(
                        serverId = ServerId("srv-1"),
                        machineId = "m-1",
                        computerName = "mac-studio",
                        isComputer = true,
                        attachedByCurrentUser = true,
                        // Only another user's agent is visible -> should NOT show "Cannot delete computer".
                        agents = listOf(RaftComputerDeleteAgentRef(creatorUserId = "u-alice", assistantOwnerUserId = null)),
                        currentUserId = "u-me",
                        repository = repo,
                        onDeleted = { deleted = true },
                        onDismiss = {},
                        timeContext = fixedTimeContext,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Are you sure you want to delete \"mac-studio\"?").assertIsDisplayed()
        onNodeWithTag(RaftComputersRackTags.DeleteConfirm).performClick()
        waitForIdle()

        // Transitions to OthersAgentsOnComputerDialog listing both agents.
        onNodeWithTag(RaftComputerDeleteDialogTags.OthersAgentsDialog).assertIsDisplayed()
        onNodeWithText("Other people's agents on this computer").assertIsDisplayed()
        onNodeWithText("Scout").assertIsDisplayed()
        onNodeWithText("Owner: Alice").assertIsDisplayed()
        onNodeWithText("Orphan Assistant").assertIsDisplayed()
        onNodeWithText("No owner").assertIsDisplayed()
        onNodeWithText("It's a personal assistant whose owner has left: a server admin can delete it.").assertIsDisplayed()

        // Ownerless agent's Notify button is disabled; Alice's is enabled.
        onNodeWithTag(RaftComputerDeleteDialogTags.notifyButton("a-pa")).assertIsNotEnabled()
        onNodeWithTag(RaftComputerDeleteDialogTags.notifyButton("a-alice")).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf(listOf("a-alice")), repo.notifyCalls)
        onNodeWithTag(RaftComputerDeleteDialogTags.notifiedLabel("a-alice")).assertIsDisplayed()
        onNodeWithText("Remind again").assertIsDisplayed()

        // Open Force Unbind dialog -> verify destructive banner and agent metadata -> Cancel goes back preserving notified state.
        onNodeWithTag(RaftComputerDeleteDialogTags.OthersAgentsForceButton).performClick()
        waitForIdle()
        onNodeWithTag(RaftComputerDeleteDialogTags.ForceUnbindBody).assertIsDisplayed()
        onNodeWithText("Force unbind 2 agents?").assertIsDisplayed()
        onNodeWithText("Owner: Alice · mac-studio").assertIsDisplayed()
        onNodeWithText("No owner · mac-studio").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithTag(RaftComputerDeleteDialogTags.notifiedLabel("a-alice")).assertIsDisplayed()

        // Open Force Unbind again and confirm -> sends exact forceAgentIds and invokes onDeleted.
        onNodeWithTag(RaftComputerDeleteDialogTags.OthersAgentsForceButton).performClick()
        waitForIdle()
        onNodeWithTag(RaftComputerDeleteDialogTags.ForceUnbindConfirm).performClick()
        waitForIdle()
        assertEquals(listOf(emptyList(), listOf("a-alice", "a-pa")), repo.revokeForceCalls)
        assertTrue(deleted)
    }

    @Test
    fun ownComputerWithOwnAgentOrForeignComputerWithAnyAgentShowsCannotDeleteNotice() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val repo = FakeOthersAgentsRepository()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftComputerDeleteFlowDialog(
                        serverId = ServerId("srv-1"),
                        machineId = "m-1",
                        computerName = "mac-studio",
                        isComputer = true,
                        attachedByCurrentUser = true,
                        agents = listOf(RaftComputerDeleteAgentRef(creatorUserId = "u-me", assistantOwnerUserId = null)),
                        currentUserId = "u-me",
                        repository = repo,
                        onDeleted = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Cannot delete computer").assertIsDisplayed()
        onNodeWithText("Remove all agents", substring = true).assertIsDisplayed()
    }

    @Test
    fun forceConflictUpdatesAgentListAndNotifyHandlesRateLimitAndUndelivered() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val first =
            ComputerOthersAgent(
                id = "a-1",
                name = "Scout",
                owner = ComputerAgentOwner("u-1", "Alice"),
                ownerNotifiedAt = null,
                ownerNextNotifyAt = null,
                personalAssistant = false,
            )
        val second =
            ComputerOthersAgent(
                id = "a-2",
                name = "Newly Added",
                owner = ComputerAgentOwner("u-2", "Bob"),
                ownerNotifiedAt = null,
                ownerNextNotifyAt = null,
                personalAssistant = false,
            )
        var notifyAttempt = 0
        val repo =
            FakeOthersAgentsRepository(
                onRevokeAndDelete = { forceIds ->
                    if (forceIds.isEmpty()) {
                        ComputerRevokeAndDeleteResult.OthersAgents(listOf(first))
                    } else if (forceIds == listOf("a-1")) {
                        ComputerRevokeAndDeleteResult.OthersAgents(listOf(first, second))
                    } else {
                        ComputerRevokeAndDeleteResult.Deleted
                    }
                },
                onNotify = {
                    notifyAttempt += 1
                    if (notifyAttempt == 1) {
                        DataResult.Content(
                            ComputerNotifyAgentOwnersResult(
                                skipped = listOf(ComputerSkippedOwnerEntry("a-1", ComputerNotifySkipReason.NotDelivered, null))
                            )
                        )
                    } else {
                        DataResult.Content(
                            ComputerNotifyAgentOwnersResult(
                                skipped = listOf(ComputerSkippedOwnerEntry("a-1", ComputerNotifySkipReason.RateLimited, null))
                            )
                        )
                    }
                },
            )
        var deleted = false
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftComputerDeleteFlowDialog(
                        serverId = ServerId("srv-1"),
                        machineId = "m-1",
                        computerName = "mac-studio",
                        isComputer = true,
                        attachedByCurrentUser = true,
                        agents = emptyList(),
                        currentUserId = "u-me",
                        repository = repo,
                        onDeleted = { deleted = true },
                        onDismiss = {},
                        timeContext = fixedTimeContext,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(RaftComputersRackTags.DeleteConfirm).performClick()
        waitForIdle()

        // First notify returns not_delivered -> shows retry message and remains enabled.
        onNodeWithTag(RaftComputerDeleteDialogTags.notifyButton("a-1")).performClick()
        waitForIdle()
        onNodeWithText("The reminder wasn't sent. Try again.").assertIsDisplayed()
        onNodeWithTag(RaftComputerDeleteDialogTags.notifyButton("a-1")).assertIsEnabled().performClick()
        waitForIdle()

        // Second notify returns rate_limited without nextAllowedAt -> disables button & shows 24h message.
        onNodeWithText("You can remind once every 24 hours").assertIsDisplayed()
        onNodeWithTag(RaftComputerDeleteDialogTags.notifyButton("a-1")).assertIsNotEnabled()

        // Force unbind gets 409 conflict -> stays open with refreshed list and conflict banner.
        onNodeWithTag(RaftComputerDeleteDialogTags.OthersAgentsForceButton).performClick()
        waitForIdle()
        onNodeWithTag(RaftComputerDeleteDialogTags.ForceUnbindConfirm).performClick()
        waitForIdle()
        onNodeWithText("More agents were added to this computer, so nothing was unbound. Check the list and confirm again.").assertIsDisplayed()
        onNodeWithText("Newly Added").assertIsDisplayed()

        // Confirming again sends both ids and succeeds.
        onNodeWithTag(RaftComputerDeleteDialogTags.ForceUnbindConfirm).performClick()
        waitForIdle()
        assertEquals(listOf("a-1", "a-2"), repo.revokeForceCalls.last())
        assertTrue(deleted)
    }

    @Test
    fun revokedFallbackDeleteFailureShowsRevokedNotRemovedReason() = runRobolectricComposeUiTest {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val repo =
            FakeOthersAgentsRepository(
                onRevokeAndDelete = { ComputerRevokeAndDeleteResult.Revoked },
                onDeleteMachine = {
                    ComputerDeleteMachineResult.Failed(
                        error = ScreenError(code = "insufficient_role", serverCode = "insufficient_role"),
                        httpStatus = 403,
                    )
                },
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftComputerDeleteFlowDialog(
                        serverId = ServerId("srv-1"),
                        machineId = "m-1",
                        computerName = "mac-studio",
                        isComputer = true,
                        attachedByCurrentUser = true,
                        agents = emptyList(),
                        currentUserId = "u-me",
                        repository = repo,
                        onDeleted = {},
                        onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(RaftComputersRackTags.DeleteConfirm).performClick()
        waitForIdle()
        assertEquals(1, repo.deleteMachineCalls)
        onNodeWithText("only a server owner or admin can remove it now", substring = true).assertIsDisplayed()
    }

    @Test
    fun russianPluralsResolveForForceUnbindTitle() {
        StringDesc.localeType = StringDesc.LocaleType.Custom("ru")
        assertEquals("Принудительно отвязать 1 агента?", Res.string.settings_computers_force_unbind_title_one.format(1).resolve())
        assertEquals("Принудительно отвязать 2 агента?", Res.string.settings_computers_force_unbind_title_other.format(2).resolve())
        assertEquals("Принудительно отвязать 5 агентов?", Res.string.settings_computers_force_unbind_title_other.format(5).resolve())
    }
}
