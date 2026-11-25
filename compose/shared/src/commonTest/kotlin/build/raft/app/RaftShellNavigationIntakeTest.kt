package build.raft.app

import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import build.raft.app.nav.RaftPageStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedWindowNavigationCoordinator
import build.raft.app.data.WindowNavigationAction
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.WindowNavigationReadiness
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import build.raft.app.ShellNavigationIntakeFixtures.SERVER
import build.raft.app.ShellNavigationIntakeFixtures.channelRoute
import build.raft.app.ShellNavigationIntakeFixtures.notificationIntent
import build.raft.app.ShellNavigationIntakeFixtures.pendingTask
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftTasksApi
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * #4260: on a phone a conversation is a page over the app shell, and a covered shell does not
 * recompose. A message link or a notification open must still be taken and opened then, with
 * nothing from the shell's composition (no effect re-run, no [RaftShellNavigationIntake.wake]
 * from it), and never pushed twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftShellNavigationIntakeTest {
    private val inbox = RaftMessageLinkInbox(nowEpochMillis = { 1_000L })
    private val coordinator = SharedWindowNavigationCoordinator()
    private val artifactInbox = RaftArtifactLinkInbox(nowEpochMillis = { 1_000L })
    private val externalInviteInbox = RaftExternalInviteLinkInbox(nowEpochMillis = { 1_000L })
    private var downloadCenterClockMillis = 1_000L
    private val downloadCenterInbox = RaftDownloadCenterLaunchInbox(nowEpochMillis = { downloadCenterClockMillis })
    private val intake =
        RaftShellNavigationIntake(
            coordinator = coordinator,
            inbox = inbox,
            artifactInbox = artifactInbox,
            externalInviteInbox = externalInviteInbox,
            downloadCenterInbox = downloadCenterInbox,
        )
    private val host = FakeShellNavigationIntakeHost()

    @Test
    fun aPendingLinkIsTakenAndOpenedWhileTheShellIsNotComposing() = intakeTest {
        // The link arrives while a conversation page covers the shell: only the platform ingress runs.
        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        advanceUntilIdle()

        assertNull(inbox.pending.value, "the link was taken")
        val open = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertEquals(MessageLinkNavigationSource, open.intent.source)
        assertEquals("channel-1", open.request.conversationIdentity.channelId?.value)
        assertEquals("message-9", open.request.anchorMessageId)
    }

    @Test
    fun aNotificationOpenIsOpenedWhileTheShellIsNotComposing() = intakeTest {
        // The notification open event's handler (outside composition) queues it; nothing else runs.
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()

        val open = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertEquals("system_notification", open.intent.source)
        assertEquals("message-1", open.request.anchorMessageId)
    }

    @Test
    fun downloadCenterLaunchOpensWhileShellIsCoveredWaitsForSessionAndDropsExpired() = intakeTest {
        // Pending while signed out: stays queued until session is ready.
        host.sessionReady = false
        downloadCenterInbox.submit(source = "android_download_notification")
        advanceUntilIdle()
        assertTrue(host.openedDownloadCenters.isEmpty())
        assertTrue(downloadCenterInbox.pending.value != null)

        // Session becomes ready and wakes intake -> taken once and delivered.
        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertNull(downloadCenterInbox.pending.value)
        assertEquals(listOf("android_download_notification"), host.openedDownloadCenters.map { it.source })

        // Repeated wakes do not re-open.
        intake.wake()
        advanceUntilIdle()
        assertEquals(1, host.openedDownloadCenters.size)

        // Expired request is dropped when taken later.
        host.sessionReady = false
        downloadCenterInbox.submit(source = "expired_notification")
        downloadCenterClockMillis += RaftMessageLinkInbox.DefaultTtlMillis + 1L
        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertNull(downloadCenterInbox.pending.value)
        assertEquals(1, host.openedDownloadCenters.size)

        // Multi-window scoping: a background shell with another shellInstanceId must NOT steal or clear
        // a launch targeted at the launcher shell.
        host.shellId = "shell:document-2"
        downloadCenterInbox.submit(source = "android_download_notification", targetShellInstanceId = "shell:default")
        advanceUntilIdle()
        assertEquals(1, host.openedDownloadCenters.size)
        assertEquals("shell:default", downloadCenterInbox.pending.value?.targetShellInstanceId)

        // Once the matching target shell wakes, it takes and opens the launch.
        host.shellId = "shell:default"
        intake.wake()
        advanceUntilIdle()
        assertNull(downloadCenterInbox.pending.value)
        assertEquals(2, host.openedDownloadCenters.size)
    }

    @Test
    fun repeatedWakesAndTheSameNotificationTwiceOpenOnce() = intakeTest {
        assertTrue(intake.enqueue(notificationIntent()))
        assertFalse(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        // The shell composes again (Back) and wakes the intake on its state changes.
        repeat(3) {
            intake.wake()
            advanceUntilIdle()
        }

        assertEquals(1, host.performed.size)
    }

    @Test
    fun anOpenWhoseConversationIsAlreadyTheTopPageIsNotPushedAgain() = intakeTest {
        host.topPage = channelRoute(host)

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        // And again when the shell composes (Back to it, a state change).
        repeat(2) {
            intake.wake()
            advanceUntilIdle()
        }

        assertTrue(host.performed.isEmpty(), "both target the page on top: ${host.performed}")
        assertEquals(2, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
        assertEquals(
            setOf(
                "message-1" to "system_notification",
                "message-9" to MessageLinkNavigationSource,
            ),
            host.focusedTopMessages.map { (req, source) -> req.anchorMessageId to source }.toSet(),
        )
        assertEquals(2, host.focusedTopMessages.size)
    }

    @Test
    fun compactPhoneWithDesktopModePreferenceOnDeduplicatesTopPageAndFocusesInPlace() = intakeTest {
        fun dmPageData(dmId: String, focusedMessageId: String? = null): JsonObject =
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive("dm:$dmId"),
                    "payload" to
                        JsonPrimitive(
                            """{"instanceId":"dm:$dmId","surfaceKind":"dm","threadId":"$dmId","streamChannelId":"$dmId"""" +
                                (focusedMessageId?.let { ""","focusedMessageId":"$it"""" } ?: "") +
                                """}""",
                        ),
                ),
            )

        val stack = RaftPageStack()
        val shell = stack.pages.single()
        val navigator = stack.navigatorFor(shell)
        val paneNavigator = RaftPaneNavigator(platform = navigator, wide = { false }, openInPane = { _, _, _ -> })
        val compactPhonePane = RaftAdaptivePaneState(layout = RaftAdaptivePaneLayout.Compact)
        val dualPaneTablet = RaftAdaptivePaneState(layout = RaftAdaptivePaneLayout.DualPane)
        var desktopPreference = DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN // true by default on every phone
        var currentPane = compactPhonePane
        host.directory =
            ShellNavigationIntakeFixtures.directoryWith(
                SharedConversationDirectoryProjection(
                    serverId = SERVER,
                    canonicalChannelId = "dm-x",
                    aliases = setOf("dm-x"),
                    conversationKind = ConversationKind.DirectMessage,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = RaftThreadSurfaceKind.DirectMessage,
                    title = "Alice",
                ),
            )
        host.topPageProvider = {
            raftResolveNavigationTopPageRoute(
                wideSplitPaneActive =
                    raftAdaptiveNavRailActive(
                        desktopModeEnabled = desktopPreference,
                        paneState = currentPane,
                    ),
                hostTopPage = paneNavigator.topPage(),
                fallbackVisibleRoute = { null },
            )
        }
        host.onPerform = { action ->
            if (action is WindowNavigationAction.OpenConversation) {
                val id = action.request.conversationIdentity.threadId.value
                stack.open(THREAD_PAGE, dmPageData(id, action.request.anchorMessageId), from = shell)
            }
        }

        // 1. Compact phone with default desktopModeOnLargeScreen = true:
        //    Push #1 (msg-1) opens DM X once.
        assertTrue(desktopPreference)
        assertTrue(intake.enqueue(notificationIntent(channelId = "dm-x", messageId = "msg-1", kind = ConversationKind.DirectMessage)))
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "first push opens DM X")
        assertEquals(2, stack.pages.size, "stack has [AppShell, DM X]")
        assertTrue(host.focusedTopMessages.isEmpty())

        //    Push #2 (msg-2) after backgrounding MUST NOT push a second DM X page, and MUST focus msg-2 in place.
        assertTrue(intake.enqueue(notificationIntent(channelId = "dm-x", messageId = "msg-2", kind = ConversationKind.DirectMessage)))
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "second push must not push duplicate DM X")
        assertEquals(2, stack.pages.size, "1 tap of Back leaves DM X back to Home")
        assertEquals(
            1,
            host.logs.count { it == "window_navigation_open_skipped reason=already_top_page source=system_notification threadId=dm-x" },
        )
        assertEquals(
            listOf("msg-2" to "system_notification"),
            host.focusedTopMessages.map { (req, source) -> req.anchorMessageId to source },
        )

        //    A top-page skip with no anchorMessageId skips without triggering in-place focus.
        assertTrue(intake.enqueue(notificationIntent(channelId = "dm-x", messageId = null, kind = ConversationKind.DirectMessage)))
        advanceUntilIdle()
        assertEquals(1, host.performed.size)
        assertEquals(2, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
        assertEquals(1, host.focusedTopMessages.size, "null anchorMessageId must not invoke focusTopConversationMessage")

        // 2. Wide dual-pane with desktop mode ON returns null (split pane handles selection in place).
        currentPane = dualPaneTablet
        desktopPreference = true
        assertNull(host.topPageRoute(), "active wide split-pane layout must return null")

        // 3. Tablet (DualPane) with desktop mode OFF still consults hostTopPage and deduplicates + focuses in place.
        desktopPreference = false
        assertTrue(intake.enqueue(notificationIntent(channelId = "dm-x", messageId = "msg-3", kind = ConversationKind.DirectMessage)))
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "tablet with desktop mode OFF must still deduplicate top page")
        assertEquals(3, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
        assertEquals(
            listOf("msg-2" to "system_notification", "msg-3" to "system_notification"),
            host.focusedTopMessages.map { (req, source) -> req.anchorMessageId to source },
        )
    }

    @Test
    fun productionStandaloneDmLaunchPayloadRoundtripsIntoTopPageDedupAndLogsMissDiagnostics() = intakeTest {
        val dmId = "af67d772-4334-4966-8a70-3b661ed18567"
        val storeScope = RaftShellStoreScope.create(shellInstanceId = "legacy-app-shell", initialServerId = SERVER)
        host.directory =
            ShellNavigationIntakeFixtures.directoryWith(
                SharedConversationDirectoryProjection(
                    serverId = SERVER,
                    canonicalChannelId = dmId,
                    aliases = setOf(dmId),
                    conversationKind = ConversationKind.DirectMessage,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    channelKind = RaftThreadSurfaceKind.DirectMessage,
                    title = "Jony",
                ),
            )
        var simulatedHostTopPage: RaftCmpPageArgs? = RaftCmpPageArgs(RAFT_APP_SHELL_PAGE, JsonObject(emptyMap()))
        host.topPageCheckProvider = {
            val resolved =
                raftResolveNavigationTopPageRoute(
                    wideSplitPaneActive = false,
                    hostTopPage = simulatedHostTopPage,
                    fallbackVisibleRoute = { null },
                )
            RaftNavigationTopPageCheck(
                route = resolved,
                diagnostics = "wideSplitPane=false hostTopPage=${simulatedHostTopPage?.pageName ?: "null"}",
            )
        }
        host.onPerform = { action ->
            if (action is WindowNavigationAction.OpenConversation) {
                val launchPayload =
                    buildStandaloneConversationThreadLaunchPayload(
                        conversation = action.request.conversationIdentity,
                        timeContext = null,
                        anchorMessageId = action.request.anchorMessageId,
                    ) ?: error("expected valid standalone DM launch payload")
                val wirePageDataJson =
                    threadLaunchPageData(launchPayload)
                        .withRaftOpenAnimation(true)
                        .withRaftShellStoreScope(storeScope)
                        .toJsonObject()
                        .toRaftJsonString()
                // Exact roundtrip through the iOS/host JSON bridge (`RaftCmpPageArgs.fromJsonText`).
                simulatedHostTopPage = RaftCmpPageArgs.fromJsonText(THREAD_PAGE, wirePageDataJson)
            }
        }

        // First push (msg1) sees AppShell on top -> logs structured miss diagnostics and pushes DM.
        assertTrue(
            intake.enqueue(
                notificationIntent(
                    channelId = dmId,
                    messageId = "19436486-1307-4539-975c-dbfd98aeec7b",
                    kind = ConversationKind.DirectMessage,
                )
            )
        )
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "first push opens DM")
        assertTrue(
            host.logs.any {
                it.startsWith("window_navigation_top_page_miss source=system_notification covered=false ") &&
                    "targetInstance=dm:$dmId" in it &&
                    "hostTopPage=$RAFT_APP_SHELL_PAGE" in it
            },
            "miss diagnostic must record target and host top page: ${host.logs}",
        )

        // Second push (msg2) after backgrounding sees the real wire-serialized DM payload on top ->
        // deduplicates via already_top_page and focuses msg2 in place without pushing a second page.
        assertTrue(
            intake.enqueue(
                notificationIntent(
                    channelId = dmId,
                    messageId = "3798ef91-a699-4bf7-a009-21173deb0083",
                    kind = ConversationKind.DirectMessage,
                )
            )
        )
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "second push must not push a duplicate DM page")
        assertEquals(
            1,
            host.logs.count { it == "window_navigation_open_skipped reason=already_top_page source=system_notification threadId=$dmId" },
        )
        assertEquals(
            listOf("3798ef91-a699-4bf7-a009-21173deb0083" to "system_notification"),
            host.focusedTopMessages.map { (req, source) -> req.anchorMessageId to source },
        )
    }

    @Test
    fun authoritativeHostStackTopDeduplicatesBackgroundNotificationsAcrossPopsAndReplaces() = intakeTest {
        fun threadPageData(channelId: String, focusedMessageId: String? = null): JsonObject =
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive("channel:$channelId"),
                    "payload" to
                        JsonPrimitive(
                            """{"instanceId":"channel:$channelId","surfaceKind":"channel","threadId":"$channelId","streamChannelId":"$channelId"""" +
                                (focusedMessageId?.let { ""","focusedMessageId":"$it"""" } ?: "") +
                                """}""",
                        ),
                ),
            )

        val stack = RaftPageStack()
        val shell = stack.pages.single()
        val navigator = stack.navigatorFor(shell)
        val paneNavigator = RaftPaneNavigator(platform = navigator, wide = { false }, openInPane = { _, _, _ -> })
        var fallbackVisibleRoute: ThreadRouteIdentity? = null
        var wideSplitPaneActive = false
        host.topPageProvider = {
            raftResolveNavigationTopPageRoute(
                wideSplitPaneActive = wideSplitPaneActive,
                hostTopPage = paneNavigator.topPage(),
                fallbackVisibleRoute = { fallbackVisibleRoute },
            )
        }

        // Open channel-1, then simulate app backgrounding (Compose visibility clears fallback to null).
        stack.open(THREAD_PAGE, threadPageData("channel-1", focusedMessageId = "message-1"), from = shell)
        fallbackVisibleRoute = null

        // Three consecutive notifications for different messages in channel-1 must all be skipped and focused in place.
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-2")))
        advanceUntilIdle()
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-3")))
        advanceUntilIdle()
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-4")))
        advanceUntilIdle()
        assertTrue(host.performed.isEmpty(), "duplicate pushes over top channel-1: ${host.performed}")
        assertEquals(3, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
        assertEquals(listOf("message-2", "message-3", "message-4"), host.focusedTopMessages.map { it.first.anchorMessageId })

        // Pushing a non-conversation page (ChannelSettings) clears top conversation even if fallback is stale.
        fallbackVisibleRoute = channelRoute(host)
        stack.open("compose_channel_settings", JsonObject(emptyMap()), from = stack.pages.last())
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-5")))
        advanceUntilIdle()
        assertEquals(1, host.performed.size, "non-thread top page must not fall back to stale visibleRoute")

        // Popping ChannelSettings back to channel-1 immediately restores channel-1 without Compose pageDidAppear.
        fallbackVisibleRoute = null
        assertTrue(stack.back())
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-6")))
        advanceUntilIdle()
        assertEquals(1, host.performed.size)
        assertEquals(4, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })
        assertEquals("message-6", host.focusedTopMessages.last().first.anchorMessageId)

        // In-place replacement of a covered page beneath a covering page never clobbers the top page.
        val pendingTask = stack.pages.last()
        stack.open("compose_channel_settings", JsonObject(emptyMap()), from = pendingTask)
        stack.open(
            THREAD_PAGE,
            JsonObject(threadPageData("channel-2") + (RAFT_REPLACE_CURRENT_PAGE to JsonPrimitive(true))),
            from = pendingTask,
        )
        assertNull(
            raftResolveNavigationTopPageRoute(
                wideSplitPaneActive = false,
                hostTopPage = paneNavigator.topPage(),
                fallbackVisibleRoute = { channelRoute(host) },
            )
        )

        // Wide split-pane layout always returns null regardless of host top page.
        assertTrue(stack.back())
        wideSplitPaneActive = true
        assertNull(host.topPageRoute())

        // Popping back to AppShell on phone layout allows notifications for channel-1 to open again.
        wideSplitPaneActive = false
        assertTrue(stack.back())
        assertEquals(RAFT_APP_SHELL_PAGE, paneNavigator.topPage()?.pageName)
        assertTrue(intake.enqueue(notificationIntent(channelId = "channel-1", messageId = "message-7")))
        advanceUntilIdle()
        assertEquals(2, host.performed.size)

        // Direct message (Hands iOS 5e33271a): consecutive background notifications for the top DM skip cleanly.
        val dmId = "dm-f0a32ee2"
        val dmPageData =
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive("dm:$dmId"),
                    "payload" to
                        JsonPrimitive("""{"instanceId":"dm:$dmId","surfaceKind":"dm","threadId":"$dmId","streamChannelId":"$dmId"}"""),
                ),
            )
        stack.open(THREAD_PAGE, dmPageData, from = shell)
        val dmRequest =
            HomeMessageOpenRequest(
                conversationIdentity =
                    ThreadLaunchConversationIdentity(
                        threadId = build.raft.app.model.ThreadId(dmId),
                        channelId = build.raft.app.model.ChannelId(dmId),
                        kind = ConversationKind.DirectMessage,
                    ),
                anchorMessageId = "dm-msg-2",
            )
        assertTrue(raftWindowNavigationTargetIsTopPage(dmRequest, host.topPageRoute()))

        // Unresolved 0-reply ReplyThread on top still matches a notification carrying the newly created streamChannelId.
        val replyPageData =
            JsonObject(
                mapOf(
                    "instanceId" to JsonPrimitive("reply-parent:channel-1:root-1"),
                    "payload" to
                        JsonPrimitive(
                            """{"instanceId":"reply-parent:channel-1:root-1","surfaceKind":"${RaftThreadSurfaceKind.ReplyThread}","parentChannelId":"channel-1","parentMessageId":"root-1","parentSurfaceKind":"${RaftThreadSurfaceKind.Channel}","parentChannelKind":"${RaftThreadSurfaceKind.Channel}"}"""
                        ),
                ),
            )
        stack.open(THREAD_PAGE, replyPageData, from = stack.pages.last())
        val replyNotificationRequest =
            HomeMessageOpenRequest(
                conversationIdentity =
                    buildThreadLaunchConversationIdentity(
                        kind = ConversationKind.Thread,
                        channelId = build.raft.app.model.ChannelId("channel-1"),
                        threadChannelId = build.raft.app.model.ChannelId("stream-99"),
                        parentChannelId = build.raft.app.model.ChannelId("channel-1"),
                        parentMessageId = build.raft.app.model.MessageId("root-1"),
                        parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                        parentChannelKind = RaftThreadSurfaceKind.Channel,
                    ),
                anchorMessageId = "reply-msg-1",
            )
        assertTrue(raftWindowNavigationTargetIsTopPage(replyNotificationRequest, host.topPageRoute()))
    }

    @Test
    fun aLinkWaitsForASessionAndOpensOnceTheShellSaysItIsReady() = intakeTest {
        host.sessionReady = false

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "android_view")
        advanceUntilIdle()
        assertTrue(host.performed.isEmpty())
        assertTrue(inbox.pending.value != null, "kept while signed out")

        host.sessionReady = true
        intake.wake()
        advanceUntilIdle()
        assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
    }

    @Test
    fun aSwitchAccountLinkWaitsUntilAnotherAccountIsSignedIn() = intakeTest {
        // Switch account: the shell still has the account being signed out of.
        externalInviteInbox.submit("tok-9", "external_invite_switch_account", notForAccountId = "account-a")
        intake.wake()
        advanceUntilIdle()
        assertTrue(host.openedInvites.isEmpty(), "not reopened for the account it isn't for")
        assertEquals("tok-9", externalInviteInbox.pending.value?.token)

        host.accountId = null
        intake.wake()
        advanceUntilIdle()
        assertTrue(host.openedInvites.isEmpty(), "nobody known yet: keep waiting")

        host.accountId = "account-b"
        intake.wake()
        advanceUntilIdle()
        assertEquals(listOf("tok-9"), host.openedInvites.map { it.token })
    }

    @Test
    fun aTaskSurfaceOverTheTopPageClosesAndALinkIntoTheConversationUnderItStillOpensAtItsMessage() = intakeTest {
        // A pending task's page is open over #general (a window dialog, not a page): the visible
        // route is still #general's.
        host.topPage = channelRoute(host)
        val cover = RaftIncomingNavigationSignal()
        host.cover = cover
        val closes = mutableListOf<Int>()
        var open = true
        cover.register {
            closes += host.performed.size
            open.also { open = false }
        }

        inbox.submit(RaftMessageLink("acme", ConversationKind.Channel, "channel-1", messageId = "message-9"), "ios_universal_link")
        advanceUntilIdle()

        val opened = assertIs<WindowNavigationAction.OpenConversation>(host.performed.single(), "not skipped as the top page")
        assertEquals("message-9", opened.request.anchorMessageId)
        assertEquals(listOf(0), closes, "the surface closed before the open")
        assertTrue(host.logs.none { it.startsWith("window_navigation_open_skipped") })

        // Nothing over the page any more: the same open is the top page again.
        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        assertEquals(1, host.performed.size)
        assertEquals(1, host.logs.count { it.startsWith("window_navigation_open_skipped reason=already_top_page") })

        // An Artifact link closes it too.
        open = true
        artifactInbox.submit(ArtifactLinkTarget("pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11", 3), "android_view")
        advanceUntilIdle()
        assertEquals(1, host.openedArtifacts.size)
        assertFalse(open, "closed before the viewer opened")
    }

    @Test
    fun anOpenWaitsForARunningFirstSendThenClosesThePendingTaskKeepingItsDraft() = intakeTest {
        val cover = RaftIncomingNavigationSignal()
        host.cover = cover
        val flow = TaskOpenSurfaceFlow(newRandomId = { "rid-1" })
        cover.register { flow.closeForIncomingNavigation() }
        val task = pendingTask()
        flow.open(TaskOpenSurface.Pending(task))
        val answer = CompletableDeferred<ThreadApiResult<Unit>>()
        val sending =
            launch {
                flow.send(
                    text = "On it",
                    serverId = SERVER,
                    tasksDataSource = SharedTasksDataSource(ConversationApi(), diagnosticsLogger = {}),
                    sendMessage = { _, _, _ -> answer.await() },
                    currentServerId = { SERVER },
                    fallbackError = "Couldn't send the message",
                )
            }
        advanceUntilIdle()
        assertTrue(flow.sending)

        assertTrue(intake.enqueue(notificationIntent()))
        advanceUntilIdle()
        assertTrue(host.performed.isEmpty(), "the send is not cut off: the open waits")
        assertEquals(task, flow.surface?.task)

        answer.complete(ThreadApiResult.Failure(code = 504))
        advanceUntilIdle()
        sending.join()
        advanceUntilIdle()
        assertIs<WindowNavigationAction.OpenConversation>(host.performed.single())
        assertNull(flow.surface, "closed once the send ended")
        assertEquals("On it", flow.draft(SERVER, task.id.value), "the unsent text stays as the draft")
    }

    /** [body] with the intake running (a foreground child, so advanceUntilIdle runs it), stopped after. */
    private fun intakeTest(body: suspend TestScope.() -> Unit) = runTest {
        val running = launch { intake.run(host) }
        advanceUntilIdle()
        body()
        running.cancel()
    }

    /** POST /tasks/:id/conversation creates the conversation. */
    private class ConversationApi : RaftTasksApi {
        override suspend fun serverTasks(serverId: ServerId, priority: build.raft.app.data.DataRequestPriority): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus): HomeNetworkResponse =
            HomeNetworkResponse(code = 501, bodyText = null)

        override suspend fun openTaskConversation(serverId: ServerId, taskId: TaskId): HomeNetworkResponse =
            HomeNetworkResponse(200, """{"conversationChannelId":"conv-local","canonicalConversationChannelId":"conv-1","created":true}""")
    }
}

/** The app shell as [RaftShellNavigationIntake] sees it, recording what it opens. */
internal class FakeShellNavigationIntakeHost : RaftShellNavigationIntakeHost {
    var sessionReady = true
    var topPage: ThreadRouteIdentity? = null
    /** Live top-page provider (defaults to [topPage]); lets tests wire an authoritative host stack. */
    var topPageProvider: () -> ThreadRouteIdentity? = { topPage }
    var topPageCheckProvider: (() -> RaftNavigationTopPageCheck)? = null
    /** The window's task surfaces; null: none registered. */
    var cover: RaftIncomingNavigationSignal? = null
    val performed = mutableListOf<WindowNavigationAction>()
    val focusedTopMessages = mutableListOf<Pair<HomeMessageOpenRequest, String>>()
    val openedArtifacts = mutableListOf<RaftPendingArtifactLink>()
    val openedInvites = mutableListOf<RaftPendingExternalInviteLink>()
    val openedDownloadCenters = mutableListOf<RaftPendingDownloadCenterLaunch>()
    var directory: SharedConversationDirectoryState = ShellNavigationIntakeFixtures.directory()
    var accountId: String? = "account-a"
    var shellId: String? = "shell:default"
    val logs = mutableListOf<String>()
    /** Runs after each [perform] is recorded (a UI test shows the opened page). */
    var onPerform: (WindowNavigationAction) -> Unit = {}

    override fun messageLinkSessionReady(): Boolean = sessionReady

    override suspend fun resolveMessageLink(taken: RaftPendingMessageLink): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = SERVER,
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = taken.link.messageId,
                    channelId = taken.link.channelId,
                    threadChannelId = taken.link.channelId,
                    conversationKind = ConversationKind.Channel,
                ),
            source = MessageLinkNavigationSource,
            launchId = "message-link-${taken.id}",
        )

    override fun openArtifactLink(taken: RaftPendingArtifactLink) {
        openedArtifacts += taken
    }

    override fun openExternalInviteLink(taken: RaftPendingExternalInviteLink) {
        openedInvites += taken
    }

    override fun openDownloadCenter(taken: RaftPendingDownloadCenterLaunch) {
        openedDownloadCenters += taken
    }

    override fun currentAccountId(): String? = accountId

    override fun shellInstanceId(): String? = shellId

    override fun readiness(): WindowNavigationReadiness =
        WindowNavigationReadiness(
            knownServerIds = setOf(SERVER),
            canonicalServerId = SERVER,
            homeSnapshotServerId = SERVER,
            homeSessionServerId = SERVER,
            conversationDirectory = directory,
        )

    override fun topPageRoute(): ThreadRouteIdentity? = topPageCheckProvider?.invoke()?.route ?: topPageProvider()

    override fun checkTopPage(): RaftNavigationTopPageCheck =
        topPageCheckProvider?.invoke() ?: RaftNavigationTopPageCheck(route = topPageProvider())

    override fun focusTopConversationMessage(request: HomeMessageOpenRequest, source: String) {
        focusedTopMessages += request to source
    }

    override suspend fun closeIncomingNavigationCover(): Boolean = cover?.closeAll() ?: false

    override suspend fun perform(action: WindowNavigationAction) {
        performed += action
        onPerform(action)
    }

    override fun log(message: String) {
        logs += message
    }
}

internal object ShellNavigationIntakeFixtures {
    const val SERVER = "server-a"

    /** A notification open of [messageId] in [channelId] (#general, #random, or a DM). */
    fun notificationIntent(
        channelId: String = "channel-1",
        messageId: String? = "message-1",
        kind: ConversationKind = ConversationKind.Channel,
    ): WindowNavigationIntent =
        WindowNavigationIntent(
            serverId = SERVER,
            source = "system_notification",
            sourceIdentity =
                ConversationOpenSourceMessageIdentity(
                    source = ConversationOpenSource.Notification,
                    messageId = messageId,
                    channelId = channelId,
                    threadChannelId = channelId,
                    conversationKind = kind,
                ),
        )

    /** #general's page route, as its ThreadPage reports it visible. */
    fun channelRoute(host: RaftShellNavigationIntakeHost): ThreadRouteIdentity {
        val probe = SharedWindowNavigationCoordinator()
        probe.enqueue(notificationIntent())
        val open = assertIs<WindowNavigationAction.OpenConversation>(probe.advance(host.readiness()!!))
        return open.request.conversationIdentity.resolveThreadRouteIdentity()
    }

    /** A v2 task in #general whose conversation is not created yet (no message in it). */
    fun pendingTask(): TaskSummary {
        val json =
            """{"id":"t-1","channelId":"channel-1","channelName":"general","channelType":"channel","taskNumber":7,
               "title":"Ship it","status":"todo","sourceMessageId":"t-1","conversationReplyCount":0,
               "createdByType":"user","createdById":"user-1","createdByName":"artin","revision":1,
               "messageId":"t-1","hostMessageId":null,"conversationChannelId":null,"conversationAnchored":true,"isLegacy":false}"""
        return parseSharedServerTasksPayload("""{"tasks":[$json]}""")?.tasks?.single() ?: error("task dropped: $json")
    }

    fun directory(): SharedConversationDirectoryState = directoryWith()

    fun directoryWith(vararg extra: SharedConversationDirectoryProjection): SharedConversationDirectoryState =
        (
            listOf("channel-1" to "General", "channel-2" to "Random").map { (id, title) ->
                SharedConversationDirectoryProjection(
                    serverId = SERVER,
                    canonicalChannelId = id,
                    aliases = setOf(id),
                    conversationKind = ConversationKind.Channel,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftThreadSurfaceKind.Channel,
                    title = title,
                )
            } + extra
        ).toSharedConversationDirectoryState()
}
