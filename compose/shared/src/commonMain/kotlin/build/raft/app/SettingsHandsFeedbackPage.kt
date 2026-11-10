package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.NetworkSettingsFeedbackSubmitRepository
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.resources.raftCurrentUiLocaleTag
import kotlinx.atomicfu.atomic
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

const val SETTINGS_HANDS_FEEDBACK_PAGE = "compose_settings_hands_feedback"

/** pageData key (intent only): opened from the ticket list, which shows a filed ticket on return. */
internal const val SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS = "settingsHandsFeedbackReturnToTickets"

@Page(SETTINGS_HANDS_FEEDBACK_PAGE, supportInLocal = true)
internal class ComposeSettingsHandsFeedbackPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.SettingsHandsFeedback) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsHandsFeedbackScreen() }
    }
}

internal fun Pager.openSettingsHandsFeedbackPage(storeScope: RaftShellStoreScope, returnToTickets: Boolean = false) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_HANDS_FEEDBACK_PAGE,
        settingsHandsFeedbackPageData(storeScope, returnToTickets)
    )
}

internal fun settingsHandsFeedbackPageData(storeScope: RaftShellStoreScope, returnToTickets: Boolean = false): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_HANDS_FEEDBACK_PAGE)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (returnToTickets) put(SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS, true)
    }

/**
 * The ticket just filed from the form the ticket list opened: the list takes it when it shows
 * again and opens it, as the web workspace opens the ticket it created. Identity only.
 */
internal object SettingsFeedbackFiledTicketHandoff {
    private val pending = atomic<String?>(null)

    fun offer(ticketId: String) {
        pending.value = ticketId
    }

    fun take(): String? = pending.getAndSet(null)
}

@Composable
private fun SettingsHandsFeedbackScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    var accountId by remember { mutableStateOf<String?>(null) }
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val returnToTickets = remember(pager.pageData.params) { pager.pageData.params.optBoolean(SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS, false) }
    val networkClient = remember(sharedStores, environment.storeScope) { sharedStores.networkClient(environment.storeScope) }

    LaunchedEffect(environment.authStore) {
        accountId = environment.authStore.readSession()?.user?.id.orEmpty()
    }

    // Web NewFeedbackForm's head: "New Feedback", then "The team replies in the ticket."
    SettingsDetailShell(
        title = settingsHandsFeedbackTitle(),
        subtitle = settingsHandsFeedbackSubtitle(),
        onBack = { router.closePage() }
    ) {
        val resolvedAccountId = accountId
        if (resolvedAccountId == null) {
            RaftCenteredLoading()
        } else {
            // Signed in (this page is authenticated): file through the Raft server, as the web does.
            val proxySubmitter =
                remember(networkClient, environment.settingsCapabilities, resolvedAccountId) {
                    SettingsFeedbackProxySubmitter(
                        repository =
                            NetworkSettingsFeedbackSubmitRepository(
                                networkClient = networkClient,
                                clientKind = raftFeedbackWebViewClientKind(),
                                clientVersion = RaftAppIdentityRegistry.current()?.version.orEmpty(),
                                logger = bridge::log,
                            ),
                        diagnostics = environment.settingsCapabilities.feedbackDiagnosticsUploader(),
                        accountId = resolvedAccountId,
                        locale = ::raftCurrentUiLocaleTag,
                        logger = bridge::log,
                    )
                }
            SharedHandsFeedbackContent(
                pager = pager,
                eventBus = sharedStores.eventBus,
                settingsCapabilities = environment.settingsCapabilities,
                sourceStoreScope = environment.storeScope,
                accountId = resolvedAccountId,
                // task #176: this standalone page is outside the app shell, so the
                // form reserves the system navigation bar itself.
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                proxySubmitter = proxySubmitter,
                onTicketFiled = { ticketId ->
                    bridge.log("settings_hands_feedback_ticket_filed ticket=${ticketId.take(8)} returnToTickets=$returnToTickets")
                    if (returnToTickets) {
                        SettingsFeedbackFiledTicketHandoff.offer(ticketId)
                        router.closePage()
                    } else {
                        // Opened from elsewhere: the ticket list, on the new ticket, in its place.
                        pager.openSettingsFeedbackTicketsPage(environment.storeScope, ticketId)
                        router.closePage()
                    }
                }
            )
        }
    }
}

@Composable
internal fun settingsHandsFeedbackTitle(): String =
    build.raft.app.resources.stringResource(
        Res.string.settings_feedback_tickets_new_feedback
    )

@Composable
internal fun settingsHandsFeedbackSubtitle(): String =
    build.raft.app.resources.stringResource(
        Res.string.settings_feedback_new_meta
    )
