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
import build.raft.app.data.SettingsFeedbackCardDraft
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isFeedbackSubmitAction
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.resources.raftCurrentUiLocaleTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

const val SETTINGS_HANDS_FEEDBACK_PAGE = "compose_settings_hands_feedback"

/** pageData key (intent only): opened from the ticket list, which shows a filed ticket on return. */
internal const val SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS = "settingsHandsFeedbackReturnToTickets"

/** pageData keys for the form opened from a `feedback:submit` card: identity only. */
internal const val SETTINGS_FEEDBACK_ACTION_CARD_ID = "settingsFeedbackActionCardId"
internal const val SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID = "settingsFeedbackActionCardServerId"

/**
 * The card a feedback form is opened for (slock `docs/development/feedback-native-entry.md`,
 * "Opened from a feedback card"): the card's message id and the server the person is viewing the
 * card in. In a joint channel that viewing server can differ from the card's own server
 * ([ThreadActionCard.sourceServerId]); the draft is read and sent as operated from the viewing
 * server, so the card's server is never used here.
 */
internal data class RaftFeedbackCardEntry(
    val actionCardMessageId: String,
    val serverId: String,
)

/**
 * Whether `feedback:submit` cards open the native feedback form on [viewingServerId] (every
 * platform, 1.24; no server flag). An unknown viewing server keeps it off, since the card is read
 * and sent as operated from it; the card's button is then "Open in web".
 */
internal fun raftFeedbackCardEntryEnabled(viewingServerId: String?): Boolean = !viewingServerId.isNullOrBlank()

/**
 * The entry for tapping [card] (message [messageId]) on [viewingServerId], or null when the card
 * stays read-only: not a `feedback:submit` card, already executed, missing ids, or no viewing server.
 */
internal fun raftFeedbackCardEntryOrNull(
    messageId: String?,
    card: ThreadActionCard?,
    viewingServerId: String?,
): RaftFeedbackCardEntry? {
    if (!raftFeedbackCardEntryEnabled(viewingServerId)) return null
    if (card == null || !card.isFeedbackSubmitAction() || card.isExecuted) return null
    val id = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val server = viewingServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftFeedbackCardEntry(actionCardMessageId = id, serverId = server)
}

/** Whether a prepared `feedback:submit` card's button opens the feedback form. */
internal fun ThreadActionCard.opensFeedbackCardEntry(): Boolean = isFeedbackSubmitAction() && !isExecuted

/** Both ids or no card: a half entry opens the ordinary form. */
internal fun settingsFeedbackCardEntryFromPageData(params: JSONObject): RaftFeedbackCardEntry? {
    val id = params.optString(SETTINGS_FEEDBACK_ACTION_CARD_ID).trim().takeIf { it.isNotEmpty() } ?: return null
    val server = params.optString(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID).trim().takeIf { it.isNotEmpty() } ?: return null
    return RaftFeedbackCardEntry(actionCardMessageId = id, serverId = server)
}

/**
 * A chat's `feedback:submit` card, tapped: the native feedback form on the card's draft (prefilled,
 * editable), sent as the card's submission, then the new ticket in Settings › Feedback. A draft it
 * cannot read (already sent, no access, offline) opens the ticket list instead, as the web page did.
 */
internal fun Pager.openSettingsFeedbackCardPage(storeScope: RaftShellStoreScope, entry: RaftFeedbackCardEntry) {
    val (pageName, pageData) = settingsFeedbackCardPageRequest(storeScope, entry)
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName, pageData)
}

/** The page [openSettingsFeedbackCardPage] opens: the native feedback form, with the card's identity only. */
internal fun settingsFeedbackCardPageRequest(storeScope: RaftShellStoreScope, entry: RaftFeedbackCardEntry): Pair<String, JSONObject> =
    SETTINGS_HANDS_FEEDBACK_PAGE to settingsHandsFeedbackPageData(storeScope, cardEntry = entry)

@Page(SETTINGS_HANDS_FEEDBACK_PAGE, supportInLocal = true)
internal class ComposeSettingsHandsFeedbackPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.SettingsHandsFeedback) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsHandsFeedbackScreen() }
    }
}

internal fun Pager.openSettingsHandsFeedbackPage(
    storeScope: RaftShellStoreScope,
    returnToTickets: Boolean = false,
    cardEntry: RaftFeedbackCardEntry? = null,
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_HANDS_FEEDBACK_PAGE,
        settingsHandsFeedbackPageData(storeScope, returnToTickets, cardEntry)
    )
}

internal fun settingsHandsFeedbackPageData(
    storeScope: RaftShellStoreScope,
    returnToTickets: Boolean = false,
    cardEntry: RaftFeedbackCardEntry? = null,
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_HANDS_FEEDBACK_PAGE)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (returnToTickets) put(SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS, true)
        cardEntry?.let { entry ->
            put(SETTINGS_FEEDBACK_ACTION_CARD_ID, entry.actionCardMessageId)
            put(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID, entry.serverId)
        }
    }

/**
 * The ticket just filed from the form the ticket list opened: the list takes it as it is offered
 * ([offered], [followSettingsFeedbackFiledTickets]) and opens it, as the web workspace opens the
 * ticket it created. Identity only.
 */
internal object SettingsFeedbackFiledTicketHandoff {
    private val pending = MutableStateFlow<String?>(null)

    /** The ticket waiting for the list, or null once taken. */
    val offered: StateFlow<String?> = pending.asStateFlow()

    fun offer(ticketId: String) {
        pending.value = ticketId
    }

    fun take(): String? = pending.getAndUpdate { null }
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
    val submitRepository =
        remember(networkClient) {
            NetworkSettingsFeedbackSubmitRepository(
                networkClient = networkClient,
                clientKind = raftFeedbackWebViewClientKind(),
                clientVersion = RaftAppIdentityRegistry.current()?.version.orEmpty(),
                logger = bridge::log,
            )
        }
    val cardEntry = remember(pager.pageData.params) { settingsFeedbackCardEntryFromPageData(pager.pageData.params) }
    var cardDraft by remember(cardEntry) { mutableStateOf<SettingsFeedbackCardDraft?>(null) }
    val openTicketList: () -> Unit = {
        pager.openSettingsFeedbackTicketsPage(environment.storeScope)
        router.closePage()
    }

    LaunchedEffect(environment.authStore) {
        accountId = environment.authStore.readSession()?.user?.id.orEmpty()
    }
    // Opened from a card: its draft first (web standalone/actionCard.tsx); one it cannot read opens
    // the ticket list, as the web page opened its ordinary workspace.
    LaunchedEffect(cardEntry, submitRepository) {
        val entry = cardEntry ?: return@LaunchedEffect
        val draft = submitRepository.loadCardDraft(entry.actionCardMessageId, entry.serverId)
        if (draft == null) {
            bridge.log("settings_hands_feedback_card_unreadable open=tickets")
            openTicketList()
        } else {
            bridge.log("settings_hands_feedback_card_draft type=${draft.type.wireValue} length=${draft.message.length}")
            cardDraft = draft
        }
    }

    // Web NewFeedbackForm's head: "New Feedback", then "The team replies in the ticket."
    SettingsDetailShell(
        title = settingsHandsFeedbackTitle(),
        subtitle = settingsHandsFeedbackSubtitle(),
        onBack = { router.closePage() }
    ) {
        val resolvedAccountId = accountId
        val card = cardDraft
        if (resolvedAccountId == null || (cardEntry != null && card == null)) {
            RaftCenteredLoading()
        } else {
            // Signed in (this page is authenticated): file through the Raft server, as the web does.
            val proxySubmitter =
                remember(submitRepository, environment.settingsCapabilities, resolvedAccountId) {
                    SettingsFeedbackProxySubmitter(
                        repository = submitRepository,
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
                card = card,
                onCardAlreadySent =
                    card?.let {
                        {
                            bridge.log("settings_hands_feedback_card_already_sent open=tickets")
                            openTicketList()
                        }
                    },
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
