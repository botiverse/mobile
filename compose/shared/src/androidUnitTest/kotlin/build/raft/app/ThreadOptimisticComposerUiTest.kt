package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE
import build.raft.app.data.RAFT_APP_LOGIN_MMKV_NAMESPACE
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.RAFT_DEVICE_STATE_MMKV_NAMESPACE
import build.raft.app.data.RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.data.RaftSharedKeyValueStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedMembersChannelSuggestionSlice
import build.raft.app.data.SharedMembersMemberSuggestionSlice
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.EmptyRaftMemberAvatarResolver
import build.raft.app.members.LocalRaftMemberAppBotResolver
import build.raft.app.members.raftStaticMemberAppBotResolver
import build.raft.app.resources.StringDesc
import build.raft.app.settings.observe
import build.raft.app.thread.LocalThreadReverseLayout
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.RaftThreadTab
import build.raft.app.thread.ReplyParentAuthorityStatus
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadComposerRestingHeight
import build.raft.app.thread.ThreadComposerRestingHeightPlaceholderTestTag
import build.raft.app.thread.ThreadEventHandler
import build.raft.app.thread.ThreadHeaderDisplayModel
import build.raft.app.thread.ThreadLatestJumpOutcome
import build.raft.app.thread.ThreadLinkedTasksProjection
import build.raft.app.thread.ThreadLoadingOptimisticComposerHost
import build.raft.app.thread.ThreadNewerPageLoadOutcome
import build.raft.app.thread.ThreadRouteForegroundBannerSlot
import build.raft.app.thread.ThreadRouteMountPlan
import build.raft.app.thread.ThreadRouteScaffold
import build.raft.app.thread.ThreadRouteScaffoldStage
import build.raft.app.thread.dispatch
import build.raft.app.thread.ensureThreadComposerDraftStarted
import build.raft.app.thread.rememberThreadDeferredLoadingScaffoldSlots
import build.raft.app.thread.rememberThreadPageContentScaffoldSlots
import build.raft.app.thread.resolvedAuxiliaryThreadId
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.thread.threadRouteMountPlan
import build.raft.app.thread.toThreadTimelineSlice
import build.raft.app.threadComposerDraftKey
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI verification for optimistic first-frame composer rendering
 * (dm:@artin:#164, Hands 2f972fb1 / #wg-mobile-feedback:#285):
 * - [rememberThreadPageContentScaffoldSlots] renders the real interactive composer immediately
 *   while `accessResolution == Loading` (never the blank `ThreadComposerRestingHeightPlaceholder`),
 *   preserves focus and typed text across `Loading -> Resolved` without remounting, and swaps
 *   cleanly to Join / Archived / NoPermission while keeping the draft in the store if authority
 *   settles negatively.
 * - [rememberThreadDeferredLoadingScaffoldSlots] (cold `LoadingShell`) renders the real optimistic
 *   composer on Frame 1 inside the single persistent [ThreadRouteScaffold] composer slot so
 *   transitioning `LoadingShell -> FeatureSurfaces` (both non-empty channels and uncached empty
 *   DMs) preserves BOTH live editor focus (`assertIsFocused()`) and typed draft without detaching
 *   the editor, and omits the composer when the route is known unjoined / archived / locked / error.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ThreadOptimisticComposerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-optimistic-composer-ui-test"
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest
    fun setUp() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        ThreadComposerRestingHeight.clearForTest()
        PagerManager.register(livePager)
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        build.raft.app.image.raftInstallImageLoader()
        listOf(
            RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            RaftLocalPreferenceStore.KuiklyNamespace,
            RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE,
            RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE,
            RAFT_DEVICE_STATE_MMKV_NAMESPACE,
            RAFT_APP_LOGIN_MMKV_NAMESPACE,
            SharedApiEnvironmentStore.Namespace,
        ).forEach { namespace ->
            val store = InMemoryRaftKeyValueStore()
            RaftSharedKeyValueStoreProvider.installAppScopeFactory(namespace) { store }
        }
    }

    @AfterTest
    fun tearDown() {
        PagerManager.unregister(livePager)
        StringDesc.localeType = previousLocale
        ThreadComposerRestingHeight.clearForTest()
        RaftSharedStoreRegistry.clearForTests()
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }

    @Test
    fun threadPageContentShowsRealComposerWhileLoadingPreservesDraftOnResolveAndSwapsOnNegativeAuthority() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val persistSlice =
                SharedThreadComposerDraftPersistSlice(
                    SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
                )
            val draftViewModel = SharedThreadComposerDraftViewModel(persistSlices = listOf(persistSlice))
            val eventBus = noopEventBus()
            val baseReplyPayload =
                RaftThreadScreenPayload(
                    instanceId = ReplyThreadId,
                    surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                    parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                    parentChannelId = ParentChannelId,
                    parentMessageId = ParentMessageId,
                    originParentMessageId = ParentMessageId,
                    sourceThreadId = ReplyThreadId,
                    streamChannelId = ReplyThreadId,
                    status = "content",
                    title = "wg-mobile-feedback",
                    joined = false,
                    canSend = false,
                    showComposer = true,
                    messages =
                        listOf(
                            RaftThreadMessageItem(
                                id = "r1",
                                authorId = "u1",
                                authorName = "Artin",
                                body = "cached reply",
                            )
                        ),
                    composer = RaftThreadComposerPayload(instanceId = ReplyThreadId, placeholder = ReplyPlaceholder),
                )

            var uiPayload by mutableStateOf(baseReplyPayload)
            var uiAccess by mutableStateOf(ThreadAccessResolution.Loading)
            var uiWriteEnabled by mutableStateOf(false)
            var uiParentAuthority by mutableStateOf(ReplyParentAuthorityStatus.Resolving)

            setContent {
                val resolvedAuxId = uiPayload.resolvedAuxiliaryThreadId()
                val baseComposer =
                    remember(uiPayload) {
                        uiPayload
                            .resolveThreadComposer(displayName = uiPayload.title)
                            .copy(streamChannelId = resolvedAuxId)
                    }
                val composerSurface =
                    remember(baseComposer, resolvedAuxId) {
                        baseComposer
                            .toThreadComposerSurface(
                                channelId = resolvedAuxId.orEmpty(),
                                parentChannelId = uiPayload.parentChannelId,
                            )
                            .withTaskV2()
                    }
                val draftKey = remember(composerSurface) { composerSurface.threadComposerDraftKey() }
                remember(draftViewModel, baseComposer.instanceId, draftKey) {
                    ensureThreadComposerDraftStarted(draftViewModel, baseComposer, draftKey)
                }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        val slots =
                            rememberThreadPageContentScaffoldSlots(
                                threadInstanceKey = ReplyThreadId,
                                routeActive = true,
                                hostForeground = true,
                                payload = uiPayload,
                                messageBodyTextStyle = TextStyle.Default,
                                timelineSlice = uiPayload.toThreadTimelineSlice(),
                                accessResolution = uiAccess,
                                canonicalAccessResolved = uiAccess != ThreadAccessResolution.Loading,
                                replyParentAuthority = uiParentAuthority,
                                writeActionsEnabled = uiWriteEnabled,
                                replyMetadataByParentId = emptyMap(),
                                headerDisplay =
                                    ThreadHeaderDisplayModel(
                                        title = "wg-mobile-feedback",
                                        subtitle = null,
                                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                                        channelKind = "channel",
                                    ),
                                composerSurface = composerSurface,
                                baseComposerState = baseComposer,
                                composerDraftViewModel = draftViewModel,
                                composerDraftPersistSlice = persistSlice,
                                resolvedAuxiliaryThreadId = resolvedAuxId,
                                tasksViewModel = null,
                                taskChannelId = null,
                                taskIdentityOptions = emptyList(),
                                linkedTasksProjection = ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap()),
                                filesViewModel = null,
                                memberSuggestionsFlow = flowOf(SharedMembersMemberSuggestionSlice()),
                                channelSuggestionsFlow = flowOf(SharedMembersChannelSuggestionSlice()),
                                selectedTab = RaftThreadTab.Chat,
                                availableTabs = listOf(RaftThreadTab.Chat),
                                onSelectedTabChanged = {},
                                onSuggestionDemandChanged = {},
                                forwardServerId = null,
                                forwardTargetRepository = null,
                                contentExtrasVisible = true,
                                onOpenTaskSummary = { _, _ -> },
                                onTasksIntent = {},
                                onFilesAuthRequired = {},
                                onOpenFilesAttachment = { _, _ -> },
                                onDownloadFilesAttachment = {},
                                onOpenMemberProfile = {},
                                highlightedMentions = emptySet(),
                                onExportShareImage = { null },
                                onSaveShareImage = { null },
                                adaptiveStoreScope = RaftShellStoreScope.Legacy,
                                adaptiveEventBus = eventBus,
                                onLoadNewer = { ThreadNewerPageLoadOutcome.Empty },
                                onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                                onBack = {},
                                onThreadEvent = ThreadEventHandler { _, _, _, _, _, _ -> },
                                onComposerSendRequest = { ThreadComposerSendAdmission.Deferred },
                                onComposerEvent = { action, value, asTask, _ ->
                                    if (action == "change") {
                                        draftViewModel.dispatch(
                                            SharedThreadComposerDraftIntent.Change(
                                                seedText = value,
                                                seedAsTask = asTask,
                                            )
                                        )
                                    }
                                },
                            )
                        ThreadRouteScaffold(slots)
                    }
                }
            }
            waitForIdle()

            // Frame 1 during Loading: real interactive composer is shown (NOT blank placeholder!).
            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertDoesNotExist()
            onNodeWithText(ReplyPlaceholder, useUnmergedTree = true).assertIsDisplayed()
            onNode(hasSetTextAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("fast reply")
            waitForIdle()
            assertEquals("fast reply", draftViewModel.state.value.seedText)

            // Transition Loading -> Resolved: persistent scaffold slot keeps the same editor focused & typed text intact.
            uiPayload = baseReplyPayload.copy(joined = true, canSend = true)
            uiAccess = ThreadAccessResolution.Resolved
            uiWriteEnabled = true
            uiParentAuthority = ReplyParentAuthorityStatus.Writable
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertTextContains("fast reply")

            // Negative authority (unjoined): swaps to Join footer while keeping draft in store.
            uiPayload = baseReplyPayload.copy(joined = false, canSend = false, joinLabel = "Join channel")
            uiParentAuthority = ReplyParentAuthorityStatus.Denied
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Join channel", useUnmergedTree = true).assertIsDisplayed()
            assertEquals("fast reply", draftViewModel.state.value.seedText)

            // Archived channel: swaps to Archived notice while preserving draft.
            uiPayload = baseReplyPayload.copy(joined = true, canSend = false, channelArchived = true)
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()
            assertEquals("fast reply", draftViewModel.state.value.seedText)

            // NoPermission: hides composer cleanly while preserving draft.
            uiPayload = baseReplyPayload.copy(joined = false, canSend = false, channelArchived = false)
            uiAccess = ThreadAccessResolution.NoPermission
            uiWriteEnabled = false
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()
            assertEquals("fast reply", draftViewModel.state.value.seedText)
        }

    @Test
    fun coldLoadingShellShowsOptimisticComposerOnFrame1MovesIntoFeatureSurfacesAndOmitsWhenBlocked() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val persistSlice =
                SharedThreadComposerDraftPersistSlice(
                    SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
                )
            val draftViewModel = SharedThreadComposerDraftViewModel(persistSlices = listOf(persistSlice))
            val eventBus = noopEventBus()
            val coldPayload =
                RaftThreadScreenPayload(
                    instanceId = ChannelId,
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    sourceThreadId = ChannelId,
                    streamChannelId = ChannelId,
                    status = "loading",
                    title = "general",
                    joined = true,
                    canSend = true,
                    showComposer = true,
                    composer = RaftThreadComposerPayload(instanceId = ChannelId, placeholder = ChannelPlaceholder),
                )
            var payloadState by mutableStateOf(coldPayload)
            var accessState by mutableStateOf(ThreadAccessResolution.Loading)
            var writeEnabled by mutableStateOf(false)
            var composerLocked by mutableStateOf(false)

            setContent {
                val timelineSlice = payloadState.toThreadTimelineSlice()
                val mountPlan = payloadState.threadRouteMountPlan(timelineSlice, accessState)
                val headerDisplay =
                    ThreadHeaderDisplayModel(
                        title = "general",
                        subtitle = null,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                    )
                CompositionLocalProvider(
                    LocalActivity provides RaftPageActivity(livePager),
                ) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        ThreadRouteScaffoldStage(
                            isLoadingShell = mountPlan == ThreadRouteMountPlan.LoadingShell,
                            loadingSlots = {
                                rememberThreadDeferredLoadingScaffoldSlots(
                                    payload = payloadState,
                                    displayModel = headerDisplay,
                                    memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                                    onBack = {},
                                    showLoadingIndicator = true,
                                    optimisticComposerHost =
                                        ThreadLoadingOptimisticComposerHost(
                                            accessResolution = accessState,
                                            replyParentAuthority = ReplyParentAuthorityStatus.Writable,
                                            composerDraftViewModel = draftViewModel,
                                            composerLocked = composerLocked,
                                            onSendRequest = { ThreadComposerSendAdmission.Deferred },
                                            onEvent = { action, value, asTask, _ ->
                                                if (action == "change") {
                                                    draftViewModel.dispatch(
                                                        SharedThreadComposerDraftIntent.Change(
                                                            seedText = value,
                                                            seedAsTask = asTask,
                                                        )
                                                    )
                                                }
                                            },
                                        ),
                                )
                            },
                            featureSlots = {
                                val resolvedAuxId = payloadState.resolvedAuxiliaryThreadId()
                                val baseComposer =
                                    remember(payloadState) {
                                        payloadState
                                            .resolveThreadComposer(displayName = headerDisplay.title)
                                            .copy(streamChannelId = resolvedAuxId)
                                    }
                                val composerSurface =
                                    remember(baseComposer, resolvedAuxId) {
                                        baseComposer
                                            .toThreadComposerSurface(channelId = resolvedAuxId.orEmpty())
                                            .withTaskV2()
                                    }
                                val draftKey = remember(composerSurface) { composerSurface.threadComposerDraftKey() }
                                remember(draftViewModel, baseComposer.instanceId, draftKey) {
                                    ensureThreadComposerDraftStarted(draftViewModel, baseComposer, draftKey)
                                }
                                rememberThreadPageContentScaffoldSlots(
                                    threadInstanceKey = ChannelId,
                                    routeActive = true,
                                    hostForeground = true,
                                    payload = payloadState,
                                    messageBodyTextStyle = TextStyle.Default,
                                    timelineSlice = timelineSlice,
                                    accessResolution = accessState,
                                    canonicalAccessResolved = accessState == ThreadAccessResolution.Resolved,
                                    replyParentAuthority = ReplyParentAuthorityStatus.Writable,
                                    writeActionsEnabled = writeEnabled,
                                    replyMetadataByParentId = emptyMap(),
                                    headerDisplay = headerDisplay,
                                    composerSurface = composerSurface,
                                    baseComposerState = baseComposer,
                                    composerDraftViewModel = draftViewModel,
                                    composerDraftPersistSlice = persistSlice,
                                    resolvedAuxiliaryThreadId = resolvedAuxId,
                                    tasksViewModel = null,
                                    taskChannelId = null,
                                    taskIdentityOptions = emptyList(),
                                    linkedTasksProjection = ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap()),
                                    filesViewModel = null,
                                    memberSuggestionsFlow = flowOf(SharedMembersMemberSuggestionSlice()),
                                    channelSuggestionsFlow = flowOf(SharedMembersChannelSuggestionSlice()),
                                    selectedTab = RaftThreadTab.Chat,
                                    availableTabs = listOf(RaftThreadTab.Chat),
                                    onSelectedTabChanged = {},
                                    onSuggestionDemandChanged = {},
                                    forwardServerId = null,
                                    forwardTargetRepository = null,
                                    contentExtrasVisible = true,
                                    onOpenTaskSummary = { _, _ -> },
                                    onTasksIntent = {},
                                    onFilesAuthRequired = {},
                                    onOpenFilesAttachment = { _, _ -> },
                                    onDownloadFilesAttachment = {},
                                    onOpenMemberProfile = {},
                                    highlightedMentions = emptySet(),
                                    onExportShareImage = { null },
                                    onSaveShareImage = { null },
                                    adaptiveStoreScope = RaftShellStoreScope.Legacy,
                                    adaptiveEventBus = eventBus,
                                    onLoadNewer = { ThreadNewerPageLoadOutcome.Empty },
                                    onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                                    onBack = {},
                                    onThreadEvent = ThreadEventHandler { _, _, _, _, _, _ -> },
                                    onComposerSendRequest = { ThreadComposerSendAdmission.Deferred },
                                    onComposerEvent = { action, value, asTask, _ ->
                                        if (action == "change") {
                                            draftViewModel.dispatch(
                                                SharedThreadComposerDraftIntent.Change(
                                                    seedText = value,
                                                    seedAsTask = asTask,
                                                )
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
            waitForIdle()

            // Cold LoadingShell Frame 1: real composer is mounted immediately!
            assertEquals(
                ThreadRouteMountPlan.LoadingShell,
                payloadState.threadRouteMountPlan(payloadState.toThreadTimelineSlice(), accessState),
            )
            onNodeWithTag(ThreadComposerRestingHeightPlaceholderTestTag).assertDoesNotExist()
            onNodeWithText(ChannelPlaceholder, useUnmergedTree = true).assertIsDisplayed()
            onNode(hasSetTextAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("cold draft")
            waitForIdle()
            assertEquals("cold draft", draftViewModel.state.value.seedText)

            // Known blockers during LoadingShell omit the composer cleanly.
            payloadState = coldPayload.copy(joined = false)
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()

            payloadState = coldPayload.copy(joined = true, channelArchived = true)
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()

            payloadState = coldPayload.copy(joined = true, channelArchived = false)
            composerLocked = true
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertDoesNotExist()

            // Restore unblocked LoadingShell, focus editor, then transition LoadingShell -> FeatureSurfaces.
            composerLocked = false
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertTextContains("cold draft")
            onNode(hasSetTextAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()

            payloadState =
                coldPayload.copy(
                    status = "content",
                    messages =
                        listOf(
                            RaftThreadMessageItem(
                                id = "m1",
                                authorId = "u1",
                                authorName = "Artin",
                                body = "loaded message",
                            )
                        ),
                )
            accessState = ThreadAccessResolution.Resolved
            writeEnabled = true
            waitForIdle()
            assertEquals(
                ThreadRouteMountPlan.FeatureSurfaces,
                payloadState.threadRouteMountPlan(payloadState.toThreadTimelineSlice(), accessState),
            )
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertTextContains("cold draft")
            assertEquals("cold draft", draftViewModel.state.value.seedText)
        }

    @Test
    fun coldEmptyDmKeepsComposerFocusedAcrossLoadingShellToFeatureSurfaces() =
        runRobolectricComposeUiTest {
            // Regression for Hands 2f972fb1 (#wg-mobile-feedback:#285): entering an uncached empty
            // DM and tapping the optimistic composer while LoadingShell is visible must keep the
            // editor focused (never detaching FocusTargetNode / InputConnection) when network load
            // settles to canonical empty content and switches to FeatureSurfaces.
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val persistSlice =
                SharedThreadComposerDraftPersistSlice(
                    SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
                )
            val draftViewModel = SharedThreadComposerDraftViewModel(persistSlices = listOf(persistSlice))
            val eventBus = noopEventBus()
            val coldDmPayload =
                RaftThreadScreenPayload(
                    instanceId = DmInstanceId,
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    sourceThreadId = DmChannelId,
                    streamChannelId = DmChannelId,
                    status = "loading",
                    title = "IT-fluence-root",
                    joined = true,
                    canSend = true,
                    showComposer = true,
                    composer = RaftThreadComposerPayload(instanceId = DmInstanceId, placeholder = DmPlaceholder),
                )
            var payloadState by mutableStateOf(coldDmPayload)
            var accessState by mutableStateOf(ThreadAccessResolution.Loading)
            var writeEnabled by mutableStateOf(false)

            setContent {
                val timelineSlice = payloadState.toThreadTimelineSlice()
                val mountPlan = payloadState.threadRouteMountPlan(timelineSlice, accessState)
                val headerDisplay =
                    ThreadHeaderDisplayModel(
                        title = "IT-fluence-root",
                        subtitle = null,
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        channelKind = "dm",
                    )
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(livePager)) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        // Standalone phone ThreadPage has no ancestor RaftForegroundBannerHandoff;
                        // exercise both RaftForegroundBannerHost and static CompositionLocal
                        // provider transitions across LoadingShell -> FeatureSurfaces.
                        ThreadRouteScaffoldStage(
                            isLoadingShell = mountPlan == ThreadRouteMountPlan.LoadingShell,
                            loadingSlots = {
                                rememberThreadDeferredLoadingScaffoldSlots(
                                    payload = payloadState,
                                    displayModel = headerDisplay,
                                    memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                                    onBack = {},
                                    showLoadingIndicator = true,
                                    optimisticComposerHost =
                                        ThreadLoadingOptimisticComposerHost(
                                            accessResolution = accessState,
                                            replyParentAuthority = ReplyParentAuthorityStatus.Writable,
                                            composerDraftViewModel = draftViewModel,
                                            onSendRequest = { ThreadComposerSendAdmission.Deferred },
                                            onEvent = { action, value, asTask, _ ->
                                                if (action == "change") {
                                                    draftViewModel.dispatch(
                                                        SharedThreadComposerDraftIntent.Change(
                                                            seedText = value,
                                                            seedAsTask = asTask,
                                                        )
                                                    )
                                                }
                                            },
                                        ),
                                )
                            },
                            featureSlots = {
                                val resolvedAuxId = payloadState.resolvedAuxiliaryThreadId()
                                val baseComposer =
                                    remember(payloadState) {
                                        payloadState
                                            .resolveThreadComposer(displayName = headerDisplay.title)
                                            .copy(streamChannelId = resolvedAuxId)
                                    }
                                val composerSurface =
                                    remember(baseComposer, resolvedAuxId) {
                                        baseComposer
                                            .toThreadComposerSurface(channelId = resolvedAuxId.orEmpty())
                                            .withTaskV2()
                                    }
                                val draftKey = remember(composerSurface) { composerSurface.threadComposerDraftKey() }
                                remember(draftViewModel, baseComposer.instanceId, draftKey) {
                                    ensureThreadComposerDraftStarted(draftViewModel, baseComposer, draftKey)
                                }
                                val featureAppBotResolver = remember { raftStaticMemberAppBotResolver(emptyMap()) }
                                rememberThreadPageContentScaffoldSlots(
                                    providedValues =
                                        arrayOf(
                                            LocalRaftMemberAppBotResolver provides featureAppBotResolver,
                                            LocalThreadReverseLayout provides true,
                                        ),
                                    foregroundBanner =
                                        ThreadRouteForegroundBannerSlot(
                                            banner = null,
                                            topSafeArea = 0.dp,
                                            onBannerClick = {},
                                            onBannerDismiss = {},
                                        ),
                                    threadInstanceKey = DmInstanceId,
                                    routeActive = true,
                                    hostForeground = true,
                                    payload = payloadState,
                                    messageBodyTextStyle = TextStyle.Default,
                                    timelineSlice = timelineSlice,
                                    accessResolution = accessState,
                                    canonicalAccessResolved = accessState == ThreadAccessResolution.Resolved,
                                    replyParentAuthority = ReplyParentAuthorityStatus.Writable,
                                    writeActionsEnabled = writeEnabled,
                                    replyMetadataByParentId = emptyMap(),
                                    headerDisplay = headerDisplay,
                                    composerSurface = composerSurface,
                                    baseComposerState = baseComposer,
                                    composerDraftViewModel = draftViewModel,
                                    composerDraftPersistSlice = persistSlice,
                                    resolvedAuxiliaryThreadId = resolvedAuxId,
                                    tasksViewModel = null,
                                    taskChannelId = null,
                                    taskIdentityOptions = emptyList(),
                                    linkedTasksProjection = ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap()),
                                    filesViewModel = null,
                                    memberSuggestionsFlow = flowOf(SharedMembersMemberSuggestionSlice()),
                                    channelSuggestionsFlow = flowOf(SharedMembersChannelSuggestionSlice()),
                                    selectedTab = RaftThreadTab.Chat,
                                    availableTabs = listOf(RaftThreadTab.Chat),
                                    onSelectedTabChanged = {},
                                    onSuggestionDemandChanged = {},
                                    forwardServerId = null,
                                    forwardTargetRepository = null,
                                    contentExtrasVisible = true,
                                    onOpenTaskSummary = { _, _ -> },
                                    onTasksIntent = {},
                                    onFilesAuthRequired = {},
                                    onOpenFilesAttachment = { _, _ -> },
                                    onDownloadFilesAttachment = {},
                                    onOpenMemberProfile = {},
                                    highlightedMentions = emptySet(),
                                    onExportShareImage = { null },
                                    onSaveShareImage = { null },
                                    adaptiveStoreScope = RaftShellStoreScope.Legacy,
                                    adaptiveEventBus = eventBus,
                                    onLoadNewer = { ThreadNewerPageLoadOutcome.Empty },
                                    onJumpToLatest = { ThreadLatestJumpOutcome.Landed },
                                    onBack = {},
                                    onThreadEvent = ThreadEventHandler { _, _, _, _, _, _ -> },
                                    onComposerSendRequest = { ThreadComposerSendAdmission.Deferred },
                                    onComposerEvent = { action, value, asTask, _ ->
                                        if (action == "change") {
                                            draftViewModel.dispatch(
                                                SharedThreadComposerDraftIntent.Change(
                                                    seedText = value,
                                                    seedAsTask = asTask,
                                                )
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
            waitForIdle()

            // Tap composer while uncached DM is still in LoadingShell.
            assertEquals(
                ThreadRouteMountPlan.LoadingShell,
                payloadState.threadRouteMountPlan(payloadState.toThreadTimelineSlice(), accessState),
            )
            onNodeWithText(DmPlaceholder, useUnmergedTree = true).assertIsDisplayed()
            onNode(hasSetTextAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()

            // Network settles to canonical empty DM -> switches LoadingShell -> FeatureSurfaces.
            payloadState = coldDmPayload.copy(status = "content", messages = emptyList())
            accessState = ThreadAccessResolution.Resolved
            writeEnabled = true
            waitForIdle()
            assertEquals(
                ThreadRouteMountPlan.FeatureSurfaces,
                payloadState.threadRouteMountPlan(payloadState.toThreadTimelineSlice(), accessState),
            )

            // Focus must remain attached without tapping again, and typing works immediately.
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("hello agent")
            waitForIdle()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
            onNode(hasSetTextAction(), useUnmergedTree = true).assertTextContains("hello agent")
            assertEquals("hello agent", draftViewModel.state.value.seedText)
        }

    private fun noopEventBus(): RaftEventBus =
        object : RaftEventBus {
            override fun observe(
                eventName: String,
                crossProcess: Boolean,
                listener: (JsonObject?) -> Unit,
            ): RealtimeSubscription = RealtimeSubscription {}

            override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) = Unit
        }

    private companion object {
        const val ChannelId = "channel-general"
        const val ChannelPlaceholder = "Message #general"
        const val ParentChannelId = "channel-wg"
        const val ParentMessageId = "m-parent"
        const val ReplyThreadId = "thread-52bdd7b9"
        const val ReplyPlaceholder = "Reply in thread"
        const val DmInstanceId = "dm:b8689d95"
        const val DmChannelId = "b8689d95"
        const val DmPlaceholder = "Message IT-fluence-root"
    }
}
