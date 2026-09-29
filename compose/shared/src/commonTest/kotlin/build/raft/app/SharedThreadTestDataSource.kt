package build.raft.app

import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.ChannelRepository
import build.raft.app.data.ConversationRepository
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.MemberRepository
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.SavedStatusRepository
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadReplyCacheIdentityTransitionAnchor
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader

/**
 * Test constructor for [SharedThreadDataSource] (task #1122). Mirrors the production secondary
 * constructor parameter-for-parameter, except that `replyCacheIdentityAuthority` — REQUIRED on the
 * real constructor — is supplied from [testThreadReplyCacheIdentityAuthority] on the given
 * [sessionStore]. Tests that assert identity behaviour pass the production constructor an explicit
 * authority instead of using this helper.
 */
internal fun testSharedThreadDataSource(
    networkApi: SharedThreadNetworkApi,
    sessionStore: SlockAuthSessionStore,
    pendingSendJournal: ThreadPendingSendJournal? = null,
    selectedServerStore: SelectedServerStore? = null,
    memberRepository: MemberRepository? = null,
    serverMembersStore: SharedServerMembersStore? = null,
    conversationRepository: ConversationRepository? = null,
    channelRepository: ChannelRepository? = null,
    homeSnapshotProvider: ((ServerId) -> HomeSnapshot?)? = null,
    channelProfileUpdateSink: suspend (ChannelProfileUpdateResult) -> Unit = {},
    messageStore: SharedThreadMessageStore? = null,
    cacheEnvironmentIdProvider: () -> String,
    apiEnvironmentIdProvider: () -> String = cacheEnvironmentIdProvider,
    replyCacheIdentityTransitionWaiter:
        (suspend (ThreadReplyCacheIdentityTransitionAnchor?) -> Boolean)? = null,
    payloadStore: SharedThreadPayloadStore? = null,
    durableLastKnownReader: ThreadDurableLastKnownReader? = null,
    enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader? = null,
    agentsApiClient: SlockAgentsApiClient? = null,
    actionCardsApiClient: SlockIntegrationsActionsPushApiClient? = null,
    savedStatusRepository: SavedStatusRepository? = null,
    readReceiptCoordinator: ReadReceiptCoordinator? = null,
    readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    threadPageSizeProvider: (ServerId?) -> Int? = { null },
    replyOriginHydrationGraceMillis: Long = 300L /* = SharedThreadZeroReplyOriginHydrationGraceMillis (private) */,
    canonicalConversationIdentityPublisher:
        suspend (ReplyCacheProvenance, build.raft.app.model.ConversationSummary) -> Boolean = { _, _ -> false },
    createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator? = null,
    diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger
): SharedThreadDataSource =
    SharedThreadDataSource(
    networkApi = networkApi,
    sessionStore = sessionStore,
    pendingSendJournal = pendingSendJournal,
    selectedServerStore = selectedServerStore,
    memberRepository = memberRepository,
    serverMembersStore = serverMembersStore,
    conversationRepository = conversationRepository,
    channelRepository = channelRepository,
    homeSnapshotProvider = homeSnapshotProvider,
    channelProfileUpdateSink = channelProfileUpdateSink,
    messageStore = messageStore,
    cacheEnvironmentIdProvider = cacheEnvironmentIdProvider,
    apiEnvironmentIdProvider = apiEnvironmentIdProvider,
    replyCacheIdentityTransitionWaiter = replyCacheIdentityTransitionWaiter,
    payloadStore = payloadStore,
    durableLastKnownReader = durableLastKnownReader,
    enteredScopeSnapshotLoader = enteredScopeSnapshotLoader,
    agentsApiClient = agentsApiClient,
    actionCardsApiClient = actionCardsApiClient,
    savedStatusRepository = savedStatusRepository,
    readReceiptCoordinator = readReceiptCoordinator,
    readReceiptOwnerFactory = readReceiptOwnerFactory,
    threadPageSizeProvider = threadPageSizeProvider,
    replyOriginHydrationGraceMillis = replyOriginHydrationGraceMillis,
    canonicalConversationIdentityPublisher = canonicalConversationIdentityPublisher,
    createAgentCoordinator = createAgentCoordinator,
    diagnosticsLogger = diagnosticsLogger,
    replyCacheIdentityAuthority = {
        testThreadReplyCacheIdentityAuthority(
                sessionStore = sessionStore,
                environmentId = cacheEnvironmentIdProvider(),
                selectedServerStore = selectedServerStore,
            ).capture()
        },
    )
