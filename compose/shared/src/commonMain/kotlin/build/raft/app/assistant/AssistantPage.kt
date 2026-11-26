package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.assistant.AssistantChatSettingUp
import build.raft.app.assistant.AssistantCreateStore
import build.raft.app.assistant.AssistantPageActions
import build.raft.app.assistant.AssistantPageScreen
import build.raft.app.assistant.AssistantPageView
import build.raft.app.assistant.HomeAssistantEntrySource
import build.raft.app.assistant.LocalThreadAssistantOtter
import build.raft.app.assistant.LocalThreadAssistantPanelEmbed
import build.raft.app.assistant.ThreadAssistantPanelConversationMissing
import build.raft.app.assistant.ThreadAssistantPanelEmbed
import build.raft.app.assistant.assistantOtterDmOpenRequest
import build.raft.app.assistant.assistantPageView
import build.raft.app.assistant.assistantPaused
import build.raft.app.assistant.assistantViewerIsGuest
import build.raft.app.assistant.rememberMyAssistantGate
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.HomeAssistantEntryMemory
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.MyMessageAlignmentPreference
import build.raft.app.data.MyAssistantCreateOutcome
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.RaftNavDestination
import build.raft.app.data.RaftNavSurface
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The personal assistant's page (web route `/s/:slug/assistant`, botiverse/slock
 * `packages/web/src/assistant/AssistantRoute.tsx`), pushed from the phone Home's Assistant row,
 * or beside the wide rail from its Assistant tab (an inline page, [AppShellRailAssistantKey]):
 * loading, then Home again when it is off, Create when there is no assistant yet, else its head
 * over the owner's DM with it.
 */
internal const val ASSISTANT_PAGE = "compose_assistant"

@Page(ASSISTANT_PAGE, supportInLocal = true)
internal class ComposeAssistantPage : AuthenticatedProductPager(AuthenticatedProductRoute.Assistant) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { AssistantRoute() }
    }
}

/**
 * Identity only (the Route / PageData rule): the shell scope (the page reads the session's server
 * and person, and "my assistant" from the shell's store) and the time display the DM's messages
 * use, as the Drafts page carries it.
 */
internal fun assistantPageData(storeScope: RaftShellStoreScope, timeContext: AppShellThreadTimeContext?): JSONObject =
    settingsStandalonePageData(ASSISTANT_PAGE, storeScope).apply {
        put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
        put("timeFormat", timeContext?.timeFormat ?: RaftTimeFormat24Hour)
    }

internal fun Pager.openAssistantPage(storeScope: RaftShellStoreScope, timeContext: AppShellThreadTimeContext?) {
    openRaftPage(pageName = ASSISTANT_PAGE, pageData = assistantPageData(storeScope, timeContext), storeScopeOverride = storeScope)
}

/**
 * Whether Home carries the Assistant row (web `mobileInline`): wherever the wide rail, which has
 * its own Assistant tab, is not on screen. The rail follows the window's width as well as the
 * "Desktop mode on large screens" setting, so the setting alone (on by default, on phones too)
 * does not say a phone has one.
 */
internal fun appShellHomeCarriesAssistantRow(navRailActive: Boolean): Boolean = !navRailActive

/**
 * The phone Home's Assistant row source (null in the wide layout, which has the rail instead): a
 * tap reports `nav_entry_selected` `assistant` / `mobile_home_shortcuts` (web
 * `trackNavEntrySelected("assistant", "mobile_home_shortcuts", false)`) and opens the page.
 */
@Composable
internal fun rememberAppShellHomeAssistantEntry(
    phone: Boolean,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    open: () -> Unit,
): HomeAssistantEntrySource? {
    val currentOpen by rememberUpdatedState(open)
    return remember(phone, sharedStores, storeScope) {
        if (!phone) {
            null
        } else {
            HomeAssistantEntrySource(
                store = sharedStores.myAssistantStore(storeScope),
                featureFlags = sharedStores.serverFeatureFlags,
                memory = HomeAssistantEntryMemory(raftSharedPreferenceStore()),
                onOpen = {
                    RaftProductEvents.navEntrySelected(
                        destination = RaftNavDestination.Assistant,
                        surface = RaftNavSurface.MobileHomeShortcuts,
                        wasActive = false,
                    )
                    currentOpen()
                },
            )
        }
    }
}

/** Who reads the page: the session's current server, the person, their role there. */
private data class AssistantPageSession(val serverId: String?, val userId: String?, val serverRole: String?)

/**
 * Wires [AssistantPageScreen]: "my assistant" (the shell's [build.raft.app.data.SharedMyAssistantStore],
 * gated like web `useMyAssistant`), the Create store, the pause from the server's roster, and the
 * owner's DM as the conversation page itself (THREAD_PAGE embedded without its head and tabs, as
 * the composer otter's panel shows it; web `ChatPanel hideHeader allowTasks={false}`).
 */
@Composable
internal fun AssistantRoute() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val params = pager.pageData.params
    val storeScope = remember(params.toString(), pager.pagerId) { params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId) }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val store = remember(sharedStores, storeScope) { sharedStores.myAssistantStore(storeScope) }
    // Beside the wide rail the page is the content area's (no Back; "close" leaves it for the tab under it).
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current
    val closePage: () -> Unit = {
        settingsPageClose(wideDetailClose) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }
    val session by produceState<AssistantPageSession?>(null, authStore) {
        val read = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        val serverId = read?.currentServerId?.takeIf(String::isNotBlank)
        value =
            AssistantPageSession(
                serverId = serverId,
                userId = read?.user?.id?.takeIf(String::isNotBlank),
                serverRole = read?.servers?.firstOrNull { it.id == serverId }?.role,
            )
    }
    val gate =
        rememberMyAssistantGate(
            store = store,
            flags = sharedStores.serverFeatureFlags,
            serverId = session?.serverId,
            userId = session?.userId,
            viewerIsGuest = assistantViewerIsGuest(session?.serverRole),
        )
    val key = gate.key
    val createScope = rememberCoroutineScope()
    val createStore =
        remember(store, key) {
            AssistantCreateStore(
                create = {
                    key?.let { store.create(it) }
                        ?: MyAssistantCreateOutcome.Failed(ScreenError("missing_identity", retryable = false))
                },
                scope = createScope,
            )
        }
    val form by createStore.state.collectAsState()
    // Until the session is read, nothing is known: the loading head (never Off, which leaves).
    val view = if (session == null) AssistantPageView.Loading else assistantPageView(gate.state, form)
    val off = view == AssistantPageView.Off
    LaunchedEffect(off) {
        if (off) {
            // Web `<Navigate to=home replace />`: not available here (flag off, not enabled, a guest).
            bridge.log("assistant_page_off server=${session?.serverId.orEmpty()}")
            closePage()
        }
    }
    val conversation = view as? AssistantPageView.Conversation
    val paused = rememberAssistantPagePaused(sharedStores, storeScope, session?.serverId, conversation?.assistant?.agentId)
    AssistantPageScreen(
        view = view,
        paused = paused,
        actions =
            AssistantPageActions(
                onBack = closePage,
                onCreate = createStore::submit,
                // Web: an owner adds seats, Free upgrades (CreateAgentDialog `seat_limit_agent_create`); iOS
                // view-only Billing opens the plan page.
                onViewPlans = {
                    if (settingsBillingOffersUpgrade()) {
                        pager.openRaftUpgradeDialog(
                            RaftUpgradeGate.SeatLimitAgentCreate,
                            storeScope,
                            seatRequestPurpose = build.raft.app.data.SeatRequestPurpose.AssistantCreate,
                        )
                    } else {
                        pager.openSettingsBillingPage(storeScope)
                    }
                },
                onOpenSettings = { conversation?.let { pager.openAssistantSettings(storeScope, session?.serverId, it.assistant.agentId) } },
            ),
    ) { shown ->
        val dmChannelId = shown.assistant.dmChannelId
        val serverId = session?.serverId
        if (dmChannelId == null || serverId == null) {
            AssistantChatSettingUp()
        } else {
            AssistantPageDm(
                sharedStores = sharedStores,
                storeScope = storeScope,
                params = params,
                serverId = serverId,
                dmChannelId = dmChannelId,
                agentId = shown.assistant.agentId,
                name = shown.assistant.name,
                onWarn = bridge::logWarn,
            )
        }
    }
}

/** Whether the server's roster says the assistant is paused (web `useAssistantPause`), live. */
@Composable
private fun rememberAssistantPagePaused(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    agentId: String?,
): Boolean {
    val members = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val flow =
        remember(members, serverId, agentId) {
            if (serverId == null || agentId == null) {
                flowOf(false)
            } else {
                members.serverMembersEntryFlow(ServerId(serverId)).map { it.assistantPaused(agentId) }.distinctUntilChanged()
            }
        }
    val initial = remember(members, serverId, agentId) { serverId != null && agentId != null && members.currentServerMembersEntry(ServerId(serverId)).assistantPaused(agentId) }
    val paused by flow.collectAsState(initial)
    return paused
}

/**
 * The owner's DM with the assistant: the conversation page itself, embedded under the page's head
 * (the path the composer otter's panel takes, [assistantOtterDmOpenRequest]). With
 * [LocalThreadAssistantPanelEmbed] it draws no head of its own and no tabs, and no otter.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AssistantPageDm(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    params: JSONObject,
    serverId: String,
    dmChannelId: String,
    agentId: String,
    name: String,
    onWarn: (String) -> Unit,
) {
    val chatLayout = remember { PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())) }
    var layout by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(chatLayout) {
        val read = runCatchingPreservingCancellation { chatLayout.getChatLayoutPreference() }.getOrNull()
        layout = (read?.myMessageAlignment == MyMessageAlignmentPreference.Right) to (read?.compactConsecutiveMessages == true)
    }
    // Wait for the reader's layout, so the DM does not open once one way and again the other.
    val (alignRight, compact) = layout ?: return
    val conversationIdentityProvider = remember(sharedStores, storeScope) { sharedStores.conversationIdentityProvider(storeScope) }
    val dmPageData =
        remember(serverId, dmChannelId, alignRight, compact) {
            assistantOtterDmOpenRequest(serverId, dmChannelId, conversationIdentityProvider)
                ?.let {
                    buildAppShellThreadPayload(
                        conversation = it.conversationIdentity,
                        timeContext = params.threadTimeCapabilities()?.staticContext(),
                        alignOwnMessagesRight = alignRight,
                        compactConsecutiveMessages = compact,
                        onWarn = onWarn,
                    )
                }
                ?.toWireThreadLaunchPayloadOrNull()
                ?.let { threadLaunchPageData(it).withRaftShellStoreScope(RaftShellStoreScope.create(storeScope.shellInstanceId, serverId)) }
        }
    LaunchedEffect(dmPageData == null) {
        if (dmPageData == null) onWarn("assistant_page_dm_unresolved dm=$dmChannelId")
    }
    if (dmPageData == null) {
        ThreadAssistantPanelConversationMissing(name)
        return
    }
    val embed = remember(agentId) { ThreadAssistantPanelEmbed(agentId = agentId, onWaitingActionCard = {}) }
    CompositionLocalProvider(
        LocalThreadAssistantPanelEmbed provides embed,
        // The DM is the assistant's own: no otter on its composer.
        LocalThreadAssistantOtter provides null,
    ) {
        RaftChildPage(pageName = THREAD_PAGE, pageData = dmPageData, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Opens the assistant's profile on its Profile tab (web `AssistantSettingsButton` and Settings ›
 * Appearance › "More {name} settings"), where its owner's Assistant section lives (permissions,
 * the otter, extra instructions, WeChat, Delete).
 */
internal fun Pager.openAssistantSettings(storeScope: RaftShellStoreScope, serverId: String?, agentId: String) {
    val pageData =
        RaftMembersPayload(instanceId = "assistant-settings", initialAgentTab = MembersAgentDetailTab.Profile.name.lowercase())
            .toMemberDetailPageData(MemberDetailIdentity(MemberId(agentId), MemberKind.Agent, serverId?.takeIf(String::isNotBlank)?.let(::ServerId)))
    if (pageData == null) {
        Utils.bridgeModule(pagerId).log("assistant_settings_open_skip reason=invalid_agent")
        return
    }
    openRaftPage(pageName = MEMBER_DETAIL_PAGE, pageData = pageData, storeScopeOverride = storeScope)
}
