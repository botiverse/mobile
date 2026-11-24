package build.raft.app

import build.raft.app.data.isTaskConversation
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.WindowNavigationIntent
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.isAuthSessionError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SearchIsoBounds
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SearchTimeRange
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.ScreenError
import build.raft.app.data.normalizeValidIanaTimeZoneOrNull
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskSummary
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.model.raftStartOfDayUtcIso
import androidx.compose.foundation.layout.offset
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.serialization.json.Json

private const val AppShellNotificationThreadPayloadKey = "slockNotificationThreadLaunchPayload"
private const val AppShellNotificationServerIdKey = "slockNotificationServerId"
private const val AppShellNotificationThreadIdKey = "slockNotificationThreadId"
private const val AppShellNotificationChannelIdKey = "slockNotificationChannelId"
private const val AppShellNotificationMessageIdKey = "slockNotificationMessageId"
private const val AppShellNotificationParentChannelIdKey = "slockNotificationParentChannelId"
private const val AppShellNotificationParentMessageIdKey = "slockNotificationParentMessageId"
private const val AppShellNotificationKindKey = "slockNotificationKind"
private const val AppShellNotificationCategoryKey = "slockNotificationCategory"
private const val AppShellNotificationTaskIdKey = "slockNotificationTaskId"
private const val AppShellNotificationTaskConversationChannelIdKey = "slockNotificationTaskConversationChannelId"
private const val AppShellNotificationTaskMessageIdKey = "slockNotificationTaskMessageId"

private val AppShellNotificationLaunchJson = Json { ignoreUnknownKeys = true }

internal typealias AppShellTimeContext = RaftTimeContext
internal typealias AppShellThreadTimeContext = RaftTimeContext

internal fun StoredAuthSession?.appShellThreadTimeFormat(): String? =
    listOf(this?.user?.preferredTimeFormat, this?.user?.timeFormat)
        .firstOrNull { it == "12h" || it == "24h" }

internal fun StoredAuthSession?.appShellPreferredTimezone(): String? =
    listOf(this?.user?.preferredTimezone, this?.user?.timezone)
        .firstNotNullOfOrNull(::normalizeValidIanaTimeZoneOrNull)

internal fun build.raft.app.compat.json.JSONObject.threadTimeCapabilities(): RaftTimeCapabilities? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    return RaftTimeContext(
        localOffsetMinutes = offset,
        timeFormat = optString("timeFormat").takeIf { it.isNotBlank() } ?: RaftTimeFormat24Hour
    ).toRaftTimeCapabilities()
}

internal data class AppShellNotificationThreadLaunchRequest(
    val threadId: String,
    val messageId: String,
    val channelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val conversationKind: ConversationKind? = null,
    val category: String? = null,
    /** Task v2: the push names the task conversation the reply was written in ([RaftTaskConversationPushTarget]). */
    val taskConversation: RaftTaskConversationPushTarget? = null,
)

internal fun notificationLaunchHomeContentReady(
    content: SharedHomeContentState.Content?,
    targetServerId: String?,
    selectedServerId: String?
): Boolean {
    val target = targetServerId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val readyContent = content ?: return false
    return readyContent.snapshot.server.id.value == target && selectedServerId == target
}

private fun JSONObject.appShellNotificationThreadLaunchRequest(): AppShellNotificationThreadLaunchRequest? {
    val threadId = optString(AppShellNotificationThreadIdKey).trim().takeIf { it.isNotBlank() }
    val messageId = optString(AppShellNotificationMessageIdKey).trim().takeIf { it.isNotBlank() }
    if (threadId != null && messageId != null) {
        return AppShellNotificationThreadLaunchRequest(
            threadId = threadId,
            messageId = messageId,
            channelId = optString(AppShellNotificationChannelIdKey).trim().takeIf { it.isNotBlank() },
            parentChannelId = optString(AppShellNotificationParentChannelIdKey).trim().takeIf { it.isNotBlank() },
            parentMessageId = optString(AppShellNotificationParentMessageIdKey).trim().takeIf { it.isNotBlank() },
            conversationKind = parseConversationKindOrNull(optString(AppShellNotificationKindKey)),
            category = optString(AppShellNotificationCategoryKey).trim().takeIf { it.isNotBlank() },
            taskConversation =
                parseRaftTaskConversationPushTarget(
                    taskId = optString(AppShellNotificationTaskIdKey),
                    taskConversationChannelId = optString(AppShellNotificationTaskConversationChannelIdKey),
                    taskMessageId = optString(AppShellNotificationTaskMessageIdKey),
                ),
        )
    }
    return legacyAppShellNotificationThreadLaunchRequest()
}

private fun JSONObject.legacyAppShellNotificationThreadLaunchRequest(): AppShellNotificationThreadLaunchRequest? {
    val source = optString(AppShellNotificationThreadPayloadKey).takeIf { it.isNotBlank() } ?: return null
    val payload = runCatching {
        AppShellNotificationLaunchJson.decodeFromString(WireThreadLaunchPayload.serializer(), source)
    }.getOrNull()?.takeIf { it.hasRouteIdentity() } ?: return null
    val threadId =
        payload.sourceThreadId?.takeIf { it.isNotBlank() }
            ?: payload.streamChannelId?.takeIf { it.isNotBlank() }
            ?: payload.threadId?.takeIf { it.isNotBlank() }
            ?: payload.parentChannelId?.takeIf { it.isNotBlank() }
            ?: return null
    val messageId =
        payload.focusedMessageId?.takeIf { it.isNotBlank() }
            ?: payload.parentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    return AppShellNotificationThreadLaunchRequest(
        threadId = threadId,
        messageId = messageId,
        channelId = payload.threadId?.takeIf { it.isNotBlank() },
        parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() },
        parentMessageId = payload.parentMessageId?.takeIf { it.isNotBlank() },
        conversationKind = if (payload.parentMessageId.isNullOrBlank()) null else ConversationKind.Thread
    )
}

private const val AppShellNotificationTargetKey = "slockNotificationTarget"
private const val AppShellNotificationAppIdKey = "slockNotificationAppId"

/** The server of a notification open that targets Activity (no conversation), else null. */
internal fun JSONObject.appShellNotificationActivityServerId(): String? =
    takeIf { optString(AppShellNotificationTargetKey).trim() == "activity" }?.appShellNotificationServerId()

/** A notification open that targets an installed App's detail (`{ type: "app" }`, slock #16546). */
internal data class AppShellNotificationAppTarget(
    val serverId: String,
    val appId: String,
)

/** The server and installed App of a notification open targeting an App's detail, else null. */
internal fun JSONObject.appShellNotificationAppTarget(): AppShellNotificationAppTarget? {
    if (optString(AppShellNotificationTargetKey).trim() != "app") return null
    val serverId = appShellNotificationServerId() ?: return null
    val appId = optString(AppShellNotificationAppIdKey).trim().takeIf { it.isNotBlank() } ?: return null
    return AppShellNotificationAppTarget(serverId = serverId, appId = appId)
}

private fun JSONObject.appShellNotificationServerId(): String? =
    optString(AppShellNotificationServerIdKey).trim().takeIf { it.isNotBlank() }

/**
 * Migration bridge for existing notification pageData. The serialized legacy thread payload is
 * still decoded above, but it enters the AppShell only as a server-qualified typed intent. Once
 * enqueued, the window-scoped coordinator owns retry, readiness, and exactly-once consumption.
 */
internal fun JSONObject.appShellWindowNavigationIntent(fallbackServerId: String?): WindowNavigationIntent? {
    val request = appShellNotificationThreadLaunchRequest() ?: return null
    val serverId = appShellNotificationServerId() ?: fallbackServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return WindowNavigationIntent(
        serverId = serverId,
        sourceIdentity = request.toConversationOpenSourceMessageIdentity(),
        source = "system_notification"
    )
}

internal fun JSONObject?.appShellSystemNotificationNavigationIntent(): WindowNavigationIntent? =
    this
        ?.takeIf {
            it.optString(AppShellNotificationKindKey).trim() in setOf("channel", "dm", "thread")
        }
        ?.appShellWindowNavigationIntent(fallbackServerId = null)

internal fun JSONObject.appShellDesktopShortcutNavigationIntent(fallbackServerId: String?): WindowNavigationIntent? {
    val targetId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyTargetId).trim().takeIf { it.isNotBlank() } ?: return null
    val serverId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyServerId).trim().takeIf { it.isNotBlank() }
        ?: fallbackServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val targetKind = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyTargetKind).trim().lowercase()
    val launchId = optString(build.raft.app.data.RaftDesktopShortcutContract.KeyLaunchId).trim().takeIf { it.isNotBlank() }
    val conversationKind = if (targetKind == build.raft.app.data.RaftDesktopShortcutContract.TargetKindChannel) {
        ConversationKind.Channel
    } else {
        ConversationKind.DirectMessage
    }
    return WindowNavigationIntent(
        serverId = serverId,
        sourceIdentity = ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.AppShortcut,
            messageId = null,
            channelId = targetId,
            threadChannelId = targetId,
            conversationKind = conversationKind
        ),
        source = "app_shortcut",
        launchId = launchId
    )
}

internal fun JSONObject?.appShellDesktopShortcutEventNavigationIntent(): WindowNavigationIntent? =
    this?.appShellDesktopShortcutNavigationIntent(fallbackServerId = null)

internal fun SharedConversationDirectoryState.notificationThreadLaunchRequest(
    request: AppShellNotificationThreadLaunchRequest,
    serverId: String = ""
): HomeMessageOpenRequest? {
    return when (
        val resolution =
            resolveOpenRequest(
                serverId = serverId,
                sourceMessageIdentity = request.toConversationOpenSourceMessageIdentity(),
                conversationDirectory = this
            )
    ) {
        is ConversationOpenResolution.Resolved -> resolution.request
        is ConversationOpenResolution.Pending -> null
    }
}

internal fun AppShellThreadTimeContext?.toSearchIsoBounds(range: SearchTimeRange): SearchIsoBounds {
    val context = this ?: return SearchIsoBounds()
    val today = context.todayEpochDay ?: return SearchIsoBounds()
    val afterDay =
        when (range) {
            SearchTimeRange.Any -> null
            SearchTimeRange.Today -> today
            SearchTimeRange.Last7Days -> today - 6L
            SearchTimeRange.Last30Days -> today - 29L
        }
    return SearchIsoBounds(
        afterIso = afterDay?.let { raftStartOfDayUtcIso(it, context.localOffsetMinutes) },
        beforeIso = context.nowEpochMillis?.let(::raftEpochMillisUtcIso)
    )
}

internal fun shouldApplyHomeAuthErrorGate(
    errorCode: String?,
    authGateState: SharedAuthGateState,
    cachedSession: StoredAuthSession?
): Boolean {
    if (!ScreenError(errorCode.orEmpty(), retryable = false).isAuthSessionError()) return false
    if (authGateState == SharedAuthGateState.Restoring) return false
    if (cachedSession != null) return false
    return true
}

internal fun appShellSessionIdentityChanged(
    cachedSession: StoredAuthSession?,
    currentSession: StoredAuthSession?
): Boolean =
    cachedSession?.user?.id != currentSession?.user?.id

internal fun appShellSessionChangeRestoreIntent(identityChanged: Boolean): SharedAuthGateIntent =
    if (identityChanged) SharedAuthGateIntent.Restore else SharedAuthGateIntent.RefreshSilently

internal enum class AppShellBootstrapAuthSignal {
    AuthRequired,
    SessionInvalidated,
    SessionChanged,
    BindingMetadataChanged
}

internal data class AppShellBootstrapAuthDecision(
    val intent: SharedAuthGateIntent,
    val resetSessionState: Boolean
)

internal fun appShellBootstrapAuthDecision(
    signal: AppShellBootstrapAuthSignal,
    identityChanged: Boolean = false
): AppShellBootstrapAuthDecision =
    when (signal) {
        AppShellBootstrapAuthSignal.AuthRequired,
        AppShellBootstrapAuthSignal.SessionInvalidated ->
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.SignedOut,
                resetSessionState = true
            )
        AppShellBootstrapAuthSignal.SessionChanged ->
            AppShellBootstrapAuthDecision(
                intent = appShellSessionChangeRestoreIntent(identityChanged),
                resetSessionState = identityChanged
            )
        AppShellBootstrapAuthSignal.BindingMetadataChanged ->
            AppShellBootstrapAuthDecision(
                intent = SharedAuthGateIntent.RefreshSilently,
                resetSessionState = false
            )
    }

internal fun appShellAuthSessionEventRestoreIntent(event: AuthSessionEvent): SharedAuthGateIntent? =
    if (event is AuthSessionEvent.BindingMetadataChanged) {
        SharedAuthGateIntent.RefreshSilently
    } else {
        null
    }

internal fun SharedAuthGateState.authGateDiagnosticsName(): String =
    when (this) {
        SharedAuthGateState.Restoring -> "restoring"
        SharedAuthGateState.AuthRequired -> "auth_required"
        is SharedAuthGateState.EmailVerificationRequired -> "email_verification_required"
        is SharedAuthGateState.ProfileSetupRequired -> "profile_setup_required"
        is SharedAuthGateState.ServerRequired -> "server_required"
        is SharedAuthGateState.Ready -> "ready"
        is SharedAuthGateState.BootstrapRecoveryRequired -> "qa_bootstrap_recovery"
        is SharedAuthGateState.Error -> "error"
    }

internal fun buildAppShellThreadPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext? = null,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    accessState: ThreadLaunchAccessState = ThreadLaunchAccessState(),
    onWarn: (String) -> Unit = {}
): RaftThreadScreenPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    // Trust the source tuple: parent channel+message ids alone are enough to open the
    // reply thread. Only a missing tuple is unrecoverable here; an unresolved parent
    // kind stays observable via parentSurfaceKind/parentChannelKind = null (#304/#307).
    if (isReplyThread && routeIdentity.parentKey == null) {
        onWarn("thread_launch_rejected reason=reply_identity_incomplete source=app_shell threadId=${conversation.threadId.value}")
        return null
    }
    val streamChannelId =
        if (isReplyThread) {
            conversation.threadStreamChannelId()
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
    val parentChannelId =
        if (isReplyThread) {
            conversation.threadParentChannelId()
        } else {
            null
        }
    val parentMessageId =
        if (isReplyThread) {
            conversation.threadParentMessageId()
        } else {
            null
        }
    val routeChannelKind = routeIdentity.channelKind
    val parentSurfaceKind =
        if (isReplyThread) {
            // Defensive: every live path resolves the parent kind (resolver-only exits,
            // t5/#419). A null here means a caller bypassed the resolver — log loudly; if
            // this ever fires it escalates to a t8 hardening item.
            conversation.threadParentSurfaceKind()
        } else {
            null
        }
    val parentChannelKind =
        if (isReplyThread) {
            conversation.threadParentChannelKind()
        } else {
            null
        }
    val originParentMessageId =
        if (isReplyThread) {
            parentMessageId
        } else {
            null
        }
    val canSend = accessState.joined && accessState.permissions.canSend
    // Task v2: a task conversation is a sub-conversation (no tabs, members, settings or task sends).
    val isTaskConversation = conversation.kind == ConversationKind.Task
    // Joint channels have task surfaces like any channel (server task surface mapping).
    val supportsChannelTasks = accessState.permissions.canCreateTask && !isTaskConversation
    val composer =
        RaftThreadComposerPayload(
            surfaceKind = surfaceKind,
            enabled = canSend,
            showAsTaskToggle = supportsChannelTasks,
            mentionChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            streamChannelId = streamChannelId
        )
    val launchHeaderContext =
        SharedThreadHeaderContextStore.launchContext(surfaceKind, originParentMessageId)
    val originHeaderContext =
        SharedThreadHeaderContextStore.launchOriginContext(
            surfaceKind = surfaceKind,
            routeIdentity = routeIdentity,
            originMessage = null,
            parentMessageId = parentMessageId,
            originFallback = originFallback
        )
    val payload = RaftThreadScreenPayload(
        instanceId = "shared-shell-thread-${conversation.threadId.value}",
        title = launchHeaderContext.title,
        subtitle = null,
        status = "loading",
        surfaceKind = surfaceKind,
        channelKind = routeChannelKind ?: conversation.channelType?.takeIf { it.isNotBlank() } ?: conversation.kind.name.lowercase(),
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind = parentSurfaceKind,
        parentChannelKind = parentChannelKind,
        dmPeerIdentity = conversation.dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
        memberScopedMentions = conversation.usesMemberScopedMentions(),
        originParentMessageId = originParentMessageId,
        focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() },
        selectedTab = "chat",
        navigationLabel = "Back",
        showModeTabs = !isReplyThread && !isTaskConversation,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showOpenInChannelAction = isReplyThread && parentMessageId != null && parentSurfaceKind != null,
        joined = accessState.joined,
        canSend = canSend,
        membersCount = 0,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
        showMembersAction = !isReplyThread && !isTaskConversation && surfaceKind != RaftThreadSurfaceKind.DirectMessage,
        showSettingsAction = !isReplyThread && !isTaskConversation && surfaceKind != RaftThreadSurfaceKind.DirectMessage,
        showTaskOpenActions = supportsChannelTasks,
        showTaskStatusActions = supportsChannelTasks,
        settingsActions =
            if (isReplyThread || isTaskConversation) emptyList() else defaultThreadSettingsChannelActionCapabilities(),
        emptyTitle = "Loading conversation",
        emptyDetail = "Loading messages from this conversation.",
        composer = composer
    ).copy(
        originMessage = originHeaderContext.originMessage,
        originPreview = originHeaderContext.originPreview,
        originParentMessageId = originHeaderContext.originParentMessageId,
        originStatus = originHeaderContext.originStatus,
        originErrorCode = originHeaderContext.originErrorCode
    )
    return payload
}

internal data class ThreadLaunchAccessState(
    val joined: Boolean = true,
    val permissions: ConversationPermissions = ConversationPermissions()
)

internal fun Pager.openStandaloneThreadLaunchPayload(
    launchPayload: WireThreadLaunchPayload,
    storeScope: RaftShellStoreScope,
    /** False: shown without the push ([RAFT_OPEN_WITHOUT_ANIMATION]). */
    animated: Boolean = true,
) {
    openRaftPage(
        pageName = THREAD_PAGE,
        pageData = threadLaunchPageData(launchPayload).withRaftOpenAnimation(animated),
        storeScopeOverride = storeScope
    )
}

/** The phone's Drafts page (drafts_section_v0), pushed like Saved. */
internal fun Pager.openHomeDraftsPage(
    timeContext: AppShellThreadTimeContext?,
    storeScope: RaftShellStoreScope
) {
    val pageData =
        JSONObject().apply {
            put("pageName", HOME_DRAFTS_PAGE)
            put("destination", HomeDestinationDrafts)
            put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
        }
    openRaftPage(
        pageName = HOME_DRAFTS_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScope
    )
}

internal fun Pager.closeHomeSecondaryPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

internal fun Pager.openStandaloneHomeMessageRequest(
    bridge: build.raft.app.base.BridgeModule,
    request: HomeMessageOpenRequest,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope
): Boolean =
    openStandaloneHomeConversation(
        bridge = bridge,
        conversation = request.conversationIdentity,
        source = source,
        timeContext = timeContext,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        storeScope = storeScope,
        anchorMessageId = request.anchorMessageId,
        originFallback = request.originFallback
    )

private fun Pager.openStandaloneHomeConversation(
    bridge: build.raft.app.base.BridgeModule,
    conversation: ThreadLaunchConversationIdentity,
    source: String,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null
): Boolean {
    bridge.log(
        "home_open_conversation threadId=${conversation.threadId.value} " +
            "kind=${conversation.kind.name} source=$source anchorMessageId=${anchorMessageId.orEmpty()}"
    )
    bridge.performanceMarker(
        phase = "home_open",
        routeName = "kuikly_home",
        event = "instant",
        threadId = conversation.threadId.value,
        metadata =
            mapOf(
                "kind" to conversation.kind.name,
                "source" to source,
                "anchor" to (anchorMessageId != null).toString()
            )
    )
    val launchPayload =
        buildStandaloneConversationThreadLaunchPayload(
            conversation = conversation,
            timeContext = timeContext,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages,
            anchorMessageId = anchorMessageId,
            originFallback = originFallback,
            onWarn = { bridge.logWarn(it) }
        ) ?: return false
    bridge.log(
        "home_open_thread_launch source=$source threadId=${conversation.threadId.value} " +
            "sourceThreadId=${launchPayload.sourceThreadId.orEmpty()} surface=${launchPayload.surfaceKind} " +
            "parentChannelId=${launchPayload.parentChannelId.orEmpty()} parentMessageId=${launchPayload.parentMessageId.orEmpty()} " +
            "anchorMessageId=${anchorMessageId.orEmpty()} originParentMessageId=${launchPayload.originParentMessageId.orEmpty()}"
    )
    openStandaloneThreadLaunchPayload(launchPayload, storeScope)
    return true
}

internal fun Pager.openHomeSearchEntity(
    bridge: build.raft.app.base.BridgeModule,
    entity: SearchEntityResult,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean,
    compactConsecutiveMessages: Boolean,
    storeScope: RaftShellStoreScope
): Boolean {
    return when (val destination = entity.destinationOrNull()) {
        is HomeSearchEntityDestination.Conversation -> {
            openStandaloneHomeConversation(
                bridge = bridge,
                conversation = destination.identity,
                source = "home_search_entity",
                timeContext = timeContext,
                alignOwnMessagesRight = alignOwnMessagesRight,
                compactConsecutiveMessages = compactConsecutiveMessages,
                    storeScope = storeScope
            )
        }
        is HomeSearchEntityDestination.Member -> {
            val pageData = homeSearchMemberDetailPageData(destination.identity, timeContext) ?: return false
            openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                storeScopeOverride = storeScope
            )
            true
        }
        is HomeSearchEntityDestination.Computer -> {
            openSettingsComputerPage(
                storeScope = storeScope,
                target = destination.identity,
            )
            true
        }
        null -> false
    }
}

internal fun ThreadLaunchConversationIdentity.searchPersonalizationEntityKeyOrNull(): SearchPersonalizationEntityKey? =
    when (kind) {
        ConversationKind.DirectMessage ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.DirectMessage,
                channelId?.value ?: threadId.value,
            )
        ConversationKind.Channel,
        ConversationKind.Joint ->
            SearchPersonalizationEntityKey.create(
                SearchPersonalizationEntityKind.Channel,
                channelId?.value ?: threadId.value,
            )
        ConversationKind.Thread -> {
            val parentKind =
                typedReplyParentConversationKindOrNull(
                    parentSurfaceKind = threadParentSurfaceKind(),
                    parentChannelKind = threadParentChannelKind(),
                )
            val parentId = threadParentChannelId() ?: channelId?.value
            when (parentKind) {
                ConversationKind.DirectMessage ->
                    SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.DirectMessage, parentId)
                ConversationKind.Channel,
                ConversationKind.Joint ->
                    SearchPersonalizationEntityKey.create(SearchPersonalizationEntityKind.Channel, parentId)
                else -> null
            }
        }
        ConversationKind.Inbox,
        ConversationKind.System,
        ConversationKind.Task -> null
    }

internal fun homeSearchMemberDetailPageData(
    identity: MemberDetailIdentity,
    timeContext: AppShellThreadTimeContext?,
): JSONObject? {
    val memberId = identity.memberId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val sourceServerId = identity.sourceServerId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val instanceId = "home-search-member-$memberId"
    val payload =
        JSONObject().apply {
            put("instanceId", instanceId)
            put("memberId", memberId)
            put("memberKind", identity.kind.memberKindWireValue())
            put("sourceServerId", sourceServerId)
            put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
            timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
            timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
            put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
        }
    val pageData =
        JSONObject().apply {
            put("pageName", MEMBER_DETAIL_PAGE)
            put("payload", payload.toString())
            put("instanceId", instanceId)
        }
    return pageData
}

internal fun buildStandaloneConversationThreadLaunchPayload(
    conversation: ThreadLaunchConversationIdentity,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    anchorMessageId: String? = null,
    originFallback: ThreadLaunchOriginFallback? = null,
    instanceIdOverride: String? = null,
    onWarn: (String) -> Unit = {}
): WireThreadLaunchPayload? {
    val routeIdentity = conversation.resolveThreadRouteIdentity()
    val surfaceKind = routeIdentity.surfaceKind
    val isReplyThread = surfaceKind == RaftThreadSurfaceKind.ReplyThread
    // Same contract as buildAppShellThreadPayload: source tuple alone is sufficient;
    // only a missing parentKey rejects the launch (#304/#307).
    if (isReplyThread && routeIdentity.parentKey == null) {
        onWarn("thread_launch_rejected reason=reply_identity_incomplete source=standalone threadId=${conversation.threadId.value}")
        return null
    }
    val streamChannelId =
        if (isReplyThread) {
            conversation.threadStreamChannelId()
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
    val parentChannelId = if (isReplyThread) conversation.threadParentChannelId() else null
    val parentMessageId = if (isReplyThread) conversation.threadParentMessageId() else null
    val focusedMessageId = anchorMessageId?.takeIf { it.isNotBlank() }
    return WireThreadLaunchPayload(
        instanceId = instanceIdOverride?.takeIf { it.isNotBlank() } ?: "shared-shell-thread-${conversation.threadId.value}",
        surfaceKind = surfaceKind,
        channelKind =
            when (surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread -> conversation.threadParentChannelKind()
                RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                else -> routeIdentity.channelKind
            },
        jointChannelId = conversation.jointChannelId?.takeIf { it.isNotBlank() },
        threadId =
            when (surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread,
                RaftThreadSurfaceKind.DirectMessage -> streamChannelId
                else -> null
            },
        sourceThreadId = streamChannelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        parentSurfaceKind =
            if (isReplyThread) {
                // Defensive (see buildAppShellThreadPayload): null parent kind here means a
                // resolver bypass — log loudly, never silently.
                conversation.threadParentSurfaceKind()
            } else {
                null
            },
        parentChannelKind = if (isReplyThread) conversation.threadParentChannelKind() else null,
        dmPeerIdentity = conversation.dmPeerIdentity.takeIf { surfaceKind == RaftThreadSurfaceKind.DirectMessage },
        originParentMessageId = if (isReplyThread) parentMessageId else null,
        focusedMessageId = focusedMessageId,
        focusedMessageRequestNonce =
            if (focusedMessageId != null) {
                nextThreadFocusedMessageRequestNonce()
            } else {
                0
            },
        selectedTab = "chat",
        navigationLabel = "Back",
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        localOffsetMinutes = timeContext?.localOffsetMinutes ?: 0,
        todayEpochDay = timeContext?.todayEpochDay,
        nowEpochMillis = timeContext?.nowEpochMillis,
        timeFormat = timeContext?.timeFormat ?: RaftTimeFormat24Hour,
        originFallbackParentChannelId = originFallback?.parentChannelId,
        originFallbackParentMessageId = originFallback?.parentMessageId,
        originFallbackPreview = originFallback?.preview,
        originFallbackSenderId = originFallback?.senderId,
        originFallbackSenderKind = originFallback?.senderKind,
        originFallbackExpected = originFallback?.expected ?: true
    )
}

internal fun buildStandaloneTaskThreadLaunchPayload(
    task: TaskSummary,
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    timeContext: AppShellThreadTimeContext?,
    alignOwnMessagesRight: Boolean = false,
    compactConsecutiveMessages: Boolean = false,
    parentConversationKind: ConversationKind? = null,
    /** Task v2: the local conversation `POST /tasks/:id/conversation` answered (see [taskOpenTarget]). */
    resolvedConversationChannelId: ChannelId? = null,
): WireThreadLaunchPayload? =
    buildTaskOpenLaunchPayload(
        task = task,
        resolvedConversationChannelId = resolvedConversationChannelId,
        timeContext = timeContext,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
    ) {
        val request =
            task.resolveTaskThreadOpenRequest(
                serverId = serverId,
                conversationDirectory = conversationDirectory,
                parentConversationKind = parentConversationKind
            ) ?: return@buildTaskOpenLaunchPayload null
        buildStandaloneConversationThreadLaunchPayload(
            conversation = request.conversationIdentity,
            timeContext = timeContext,
            anchorMessageId = request.anchorMessageId,
            alignOwnMessagesRight = alignOwnMessagesRight,
            compactConsecutiveMessages = compactConsecutiveMessages
        )
    }
