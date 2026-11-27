package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ChannelExternalInviteList
import build.raft.app.data.DataResult
import build.raft.app.data.ExternalInviteAnswer
import build.raft.app.data.ExternalInvitePreview
import build.raft.app.data.InviteConnectableServer
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.ThreadMemberRemovalGateState
import build.raft.app.thread.ThreadMembersPageContent
import build.raft.app.thread.dispatch
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invite people outside {server} and the Invited group on a real composition, wired as the members
 * page wires them (the store's state into [ExternalInviteRow], [ExternalInviteSheet] and
 * [ExternalInviteWithdrawDialog]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ExternalInvitesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val now = 1_763_337_600_000L // 2025-11-17T00:00:00Z

    private fun ComposeUiTest.show(source: FakeExternalInvitesDataSource, canResend: Boolean = true): ExternalInvitesStore {
        val store = ExternalInvitesStore(CoroutineScope(Dispatchers.Main.immediate), source)
        store.dispatch(ExternalInvitesIntent.Load)
        setContent {
            val state by store.state.collectAsState()
            RaftDesignSystemTheme {
                Column {
                    ExternalInviteEntryRow(serverName = "Acme", enabled = true, onClick = { store.dispatch(ExternalInvitesIntent.OpenDialog()) })
                    state.invites.forEachIndexed { index, invite ->
                        ExternalInviteRow(
                            invite = invite,
                            nowEpochMillis = now,
                            canResend = canResend,
                            busy = invite.id in state.rowBusy,
                            divider = index > 0,
                            onResend = { store.dispatch(ExternalInvitesIntent.Resend(invite.id)) },
                            onWithdraw = { store.dispatch(ExternalInvitesIntent.RequestWithdraw(invite.id)) },
                        )
                    }
                }
                state.dialog?.let { dialog ->
                    ExternalInviteSheet(
                        dialog = dialog,
                        serverName = "Acme",
                        willBecomePrivate = true,
                        joint = false,
                        channelSlotsLeft = state.slotsLeft,
                        onDismiss = { store.dispatch(ExternalInvitesIntent.CloseDialog) },
                        onTextChange = { store.dispatch(ExternalInvitesIntent.SetText(it)) },
                        onServersChange = { store.dispatch(ExternalInvitesIntent.SetServers(it)) },
                        onNext = { store.dispatch(ExternalInvitesIntent.Next) },
                        onBack = { store.dispatch(ExternalInvitesIntent.Back) },
                        onSend = { store.dispatch(ExternalInvitesIntent.Send(makePrivate = true)) },
                        onUpgrade = { store.dispatch(ExternalInvitesIntent.CloseDialog) },
                    )
                }
                state.withdrawConfirm?.let { invite ->
                    ExternalInviteWithdrawDialog(
                        invite = invite,
                        busy = state.withdrawBusy,
                        failure = state.withdrawFailure,
                        joint = false,
                        onDismiss = { store.dispatch(ExternalInvitesIntent.DismissWithdraw) },
                        onConfirm = { store.dispatch(ExternalInvitesIntent.ConfirmWithdraw) },
                    )
                }
            }
        }
        waitForIdle()
        return store
    }

    private fun ComposeUiTest.count(tag: String): Int = onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun eachInviteSaysWhereItStandsAndOffersResendOnlyWhenPending() = runRobolectricComposeUiTest {
        val source = FakeExternalInvitesDataSource()
        source.listAnswer =
            DataResult.Content(
                ExternalInviteAnswer.Ok(
                    ChannelExternalInviteList(
                        listOf(
                            externalTestInvite("i1", "a@example.com", expiresAt = "2025-12-01T00:00:00.000Z"),
                            externalTestInvite("i2", "b@example.com", awaitingApproval = true),
                            externalTestInvite("i3", "c@example.com", connecting = true, revocable = false),
                        )
                    )
                )
            )
        val store = show(source)
        assertTrue(shown("14 days left"))
        assertTrue(shown("Waiting for approval"))
        assertTrue(shown("Connecting a server"))
        assertEquals(3, count(ExternalInviteRowTag))
        assertEquals(1, count(ExternalInviteResendTag))
        // Not revocable while its conversion runs: a tap asks nothing.
        onAllNodes(hasTestTag(ExternalInviteWithdrawTag), useUnmergedTree = true)[2].performClick()
        waitForIdle()
        assertEquals(null, store.state.value.withdrawConfirm)

        onNodeWithTag(ExternalInviteResendTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("i1"), source.resendCalls)
    }

    @Test
    fun reviewBackStaysVisibleAndDisabledWhileBusy() = runRobolectricComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) {
                RaftDesignSystemTheme {
                    ExternalInviteSheet(
                        dialog =
                            ExternalInviteDialogState(
                                text = "new@example.com",
                                preview = ExternalInvitePreview(valid = listOf("new@example.com"), slotsLeft = 10),
                                busy = true,
                            ),
                        serverName = "Acme",
                        willBecomePrivate = false,
                        joint = false,
                        channelSlotsLeft = 10,
                        onDismiss = {},
                        onTextChange = {},
                        onNext = {},
                        onBack = {},
                        onSend = {},
                        onUpgrade = {},
                    )
                }
            }
        }
        waitForIdle()
        // Phone layout draws Back as an IconButton with contentDescription="Back", never morphing to Close ("Close").
        val backNode = onAllNodes(hasContentDescription("Back"), useUnmergedTree = true)
        assertTrue(backNode.fetchSemanticsNodes().isNotEmpty(), "Back control must remain present during busy")
        backNode[0].assertIsNotEnabled()
        assertEquals(0, onAllNodes(hasContentDescription("Close"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun membersPageDirectInviteButtonAndSearchMatchesOpenInvites() = runRobolectricComposeUiTest {
        var inviteOutsideClicks = 0
        val invite = externalTestInvite("i-lena", "lena@contoso.com", expiresAt = "2025-12-01T00:00:00.000Z")
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    ThreadMembersPageContent(
                        members = listOf(RaftThreadMemberItem("u1", "Artin", handle = "artin")),
                        filteredMembers = emptyList(),
                        filteredExternal = emptyList(),
                        externalMemberCount = 0,
                        candidates = emptyList(),
                        rosterQuery = TextFieldValue("lena@contoso.com"),
                        onQueryChange = {},
                        agentsExpanded = true,
                        humansExpanded = true,
                        externalExpanded = true,
                        onAgentsExpandedChange = {},
                        onHumansExpandedChange = {},
                        onExternalExpandedChange = {},
                        addDialogVisible = false,
                        onAddVisibleChange = {},
                        addMemberStatus = "idle",
                        addMemberErrorCode = null,
                        removalGate = ThreadMemberRemovalGateState(),
                        membersFailed = false,
                        membersLoading = false,
                        refreshing = false,
                        canAddMembers = true,
                        agentStatusResolver = { kotlinx.coroutines.flow.flowOf(null) },
                        memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                        onBack = {},
                        onRefresh = {},
                        onOpenMember = {},
                        onRemoveMember = {},
                        onSubmit = {},
                        externalInvites =
                            ExternalInvitesSection(
                                state = ExternalInvitesState(invites = listOf(invite), loaded = true),
                                access = true,
                                management = true,
                                serverName = "Acme",
                                nowEpochMillis = now,
                                onOpenInvite = { inviteOutsideClicks += 1 },
                            ),
                    )
                }
            }
        }
        waitForIdle()
        // Searching an invited email shows the open invite row instead of "No matches".
        assertEquals(1, count(ExternalInviteRowTag))
        assertTrue(shown("lena@contoso.com"))
        // Direct Members-page "Invite people outside Acme" button is rendered and clickable.
        assertEquals(1, count(ExternalInviteMembersPageButtonTag))
        onNodeWithTag(ExternalInviteMembersPageButtonTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, inviteOutsideClicks)
    }

    @Test
    fun step1ShowsAudienceSentenceQuietConnectServersLinkAndBackPreservesTypedEmails() = runRobolectricComposeUiTest {
        val inviteSource = FakeExternalInvitesDataSource()
        val inviteStore = ExternalInvitesStore(CoroutineScope(Dispatchers.Main.immediate), inviteSource)
        inviteStore.dispatch(ExternalInvitesIntent.OpenDialog())
        val connectSource = FakeJointConnectionsDataSource(connections = privateTestConnections().copy(channelName = "launch-week"))
        val connectStore = JointChannelConnectionsStore(connectSource, CoroutineScope(Dispatchers.Main.immediate))
        connectStore.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(memberTestAccount()))
        connectStore.dispatch(JointChannelConnectionsIntent.Load)
        setContent {
            val inviteState by inviteStore.state.collectAsState()
            val connectState by connectStore.state.collectAsState()
            RaftDesignSystemTheme {
                inviteState.dialog?.let { dialog ->
                    ExternalInviteSheet(
                        dialog = dialog,
                        serverName = "Acme",
                        willBecomePrivate = false,
                        joint = false,
                        channelSlotsLeft = 10,
                        channelName = "launch-week",
                        onConnectServers = { connectStore.dispatch(JointChannelConnectionsIntent.OpenFromExternalInvite(publicChannel = null)) },
                        onDismiss = { inviteStore.dispatch(ExternalInvitesIntent.CloseDialog) },
                        onTextChange = { inviteStore.dispatch(ExternalInvitesIntent.SetText(it)) },
                        onNext = { inviteStore.dispatch(ExternalInvitesIntent.Next) },
                        onBack = { inviteStore.dispatch(ExternalInvitesIntent.Back) },
                        onSend = { inviteStore.dispatch(ExternalInvitesIntent.Send(makePrivate = false)) },
                        onUpgrade = {},
                    )
                }
                JointMemberConnectSheet(
                    state = connectState,
                    onBack = { connectStore.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onDismiss = { connectStore.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
                    onSelectionChange = { connectStore.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
                    onSubmit = { connectStore.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
                )
            }
        }
        waitForIdle()
        assertTrue(shown("They'll get an email to join, with or without a Raft account, and see only #launch-week, including its earlier messages."))
        assertEquals(1, count(ExternalInviteConnectServersLinkTag))
        assertEquals(0, inviteSource.ownServersCalls)

        onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("lena@contoso.com")
        waitForIdle()
        assertEquals("lena@contoso.com", inviteStore.state.value.dialog?.text)

        onNodeWithTag(ExternalInviteConnectServersLinkTag, useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, count(JointMemberConnectSheetTag))

        // Back returns to Invite people outside with typed emails intact.
        onAllNodes(hasContentDescription("Back"), useUnmergedTree = true)[0].performClick()
        waitForIdle()
        assertEquals(0, count(JointMemberConnectSheetTag))
        assertEquals("lena@contoso.com", inviteStore.state.value.dialog?.text)
        assertTrue(shown("lena@contoso.com"))
    }

    @Test
    fun reviewRendersTwoStatusGroupsThatsYouAndHidesPrivateWarningForMemberOnly() = runRobolectricComposeUiTest {
        val mixedDialog =
            ExternalInviteDialogState(
                text = "x",
                preview =
                    ExternalInvitePreview(
                        valid = listOf("lena@contoso.com"),
                        alreadyMembers = listOf("kai@acme.com"),
                        alreadyInChannel = listOf("in@acme.com", "guest@other.io"),
                        alreadyInChannelMembers = listOf("in@acme.com"),
                        self = listOf("me@acme.com"),
                        alreadyPending = listOf("sam@fabrikam.com"),
                        invalid = listOf("not-an-email"),
                        slotsLeft = 10,
                    ),
            )
        setContent {
            RaftDesignSystemTheme {
                ExternalInviteSheet(
                    dialog = mixedDialog,
                    serverName = "Acme",
                    willBecomePrivate = true,
                    joint = false,
                    channelSlotsLeft = 10,
                    onDismiss = {},
                    onTextChange = {},
                    onNext = {},
                    onBack = {},
                    onSend = {},
                    onUpgrade = {},
                )
            }
        }
        waitForIdle()
        assertEquals(1, count(ExternalInviteReviewGroupInvitedTag))
        assertEquals(1, count(ExternalInviteReviewGroupNotSentTag))
        assertEquals(1, count(ExternalInvitePrivateWarningTag))
        assertTrue(shown("Invited by email"))
        assertTrue(shown("Not sent"))
        assertTrue(shown("That's you"))
        assertTrue(shown("Already in this channel"))
        assertTrue(shown("Already invited"))
        assertTrue(shown("Not an email address"))
        assertFalse(shown("Invite to connect their server"))
    }

    @Test
    fun reviewHidesPublicBecomesPrivateWarningWhenOnlyHostMembersAreEmailed() = runRobolectricComposeUiTest {
        setContent {
            RaftDesignSystemTheme {
                ExternalInviteSheet(
                    dialog =
                        ExternalInviteDialogState(
                            text = "kai@acme.com",
                            preview =
                                ExternalInvitePreview(
                                    alreadyMembers = listOf("kai@acme.com"),
                                    alreadyInChannel = listOf("in@acme.com"),
                                    alreadyInChannelMembers = listOf("in@acme.com"),
                                    slotsLeft = 10,
                                ),
                        ),
                    serverName = "Acme",
                    willBecomePrivate = true,
                    joint = false,
                    channelSlotsLeft = 10,
                    onDismiss = {},
                    onTextChange = {},
                    onNext = {},
                    onBack = {},
                    onSend = {},
                    onUpgrade = {},
                )
            }
        }
        waitForIdle()
        assertEquals(1, count(ExternalInviteReviewGroupInvitedTag))
        assertEquals(0, count(ExternalInvitePrivateWarningTag))
    }

    @Test
    fun step1AutoFocusesEmailFieldAndHidesFooterLinkWhenInlineServersShown() = runRobolectricComposeUiTest {
        setContent {
            RaftDesignSystemTheme {
                ExternalInviteSheet(
                    dialog =
                        ExternalInviteDialogState(
                            servers = listOf(InviteConnectableServer(id = "s2", name = "Botiverse", slug = "botiverse", canConnectDirectly = true)),
                        ),
                    serverName = "Acme",
                    willBecomePrivate = false,
                    joint = false,
                    channelSlotsLeft = 10,
                    onConnectServers = {},
                    onDismiss = {},
                    onTextChange = {},
                    onNext = {},
                    onBack = {},
                    onSend = {},
                    onUpgrade = {},
                )
            }
        }
        waitForIdle()
        onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
        assertEquals(0, count(ExternalInviteConnectServersLinkTag))
    }
}
