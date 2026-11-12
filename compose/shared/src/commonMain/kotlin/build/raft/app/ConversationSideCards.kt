package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.gesture.rememberRaftBackSwipeDismiss
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Task v2 (`task_v2_conversations_v0`; web ChatPanel / ChannelSideArea, raft-design
 * channel.md "Tasks and files"): a channel's or DM's Tasks and Files are cards opened from
 * header toggles instead of tabs. On a phone an open card covers the conversation under the
 * header; with both open one shows at a time under a Tasks / Files switch (Tasks first), the one
 * opened or picked last. The header's Back (and system Back) closes the open cards first.
 */
internal enum class ConversationSideCard { Tasks, Files }

/**
 * Which cards are open: one setting for the person, not per conversation, so the cards follow
 * them from channel to channel (web `channelSideCardStore`). [focus] is the card opened or
 * picked last, the one shown where only one fits.
 */
@Immutable
internal data class ConversationSideCardsState(
    val tasks: Boolean = false,
    val files: Boolean = false,
    val focus: ConversationSideCard? = null,
) {
    val anyOpen: Boolean get() = tasks || files

    fun isOpen(card: ConversationSideCard): Boolean =
        when (card) {
            ConversationSideCard.Tasks -> tasks
            ConversationSideCard.Files -> files
        }

    /** The open cards, in the order the switch lists them (Tasks above Files). */
    val openCards: List<ConversationSideCard>
        get() = ConversationSideCard.entries.filter(::isOpen)

    /** The card on screen: the last opened or picked of the open ones. */
    val shown: ConversationSideCard?
        get() = focus?.takeIf(::isOpen) ?: openCards.firstOrNull()

    fun withOpen(card: ConversationSideCard, open: Boolean): ConversationSideCardsState {
        val next =
            when (card) {
                ConversationSideCard.Tasks -> copy(tasks = open)
                ConversationSideCard.Files -> copy(files = open)
            }
        return if (open) next.copy(focus = card) else next.copy(focus = next.focus?.takeIf(next::isOpen))
    }

    fun toggled(card: ConversationSideCard): ConversationSideCardsState = withOpen(card, !isOpen(card))

    fun focused(card: ConversationSideCard): ConversationSideCardsState = if (isOpen(card)) copy(focus = card) else this

    /** Where a conversation has no Tasks (a DM without tasks), its Tasks card stays shut there. */
    fun availableIn(tasksAvailable: Boolean): ConversationSideCardsState =
        if (tasksAvailable || !tasks) this else withOpen(ConversationSideCard.Tasks, false)
}

/**
 * Persisted key of one person's open cards (web `slock:channelSideCards` in localStorage, kept
 * across reloads): `conversationSideCards:open:<environment>|<user>`, the value web's JSON
 * `{"tasks":true,"files":false}`. Per person, so another account on the device starts closed.
 */
internal const val CONVERSATION_SIDE_CARDS_OPEN_KEY_PREFIX = "conversationSideCards:open:"

internal fun conversationSideCardsOpenKey(ownerKey: String): String = "$CONVERSATION_SIDE_CARDS_OPEN_KEY_PREFIX$ownerKey"

internal fun ConversationSideCardsState.encodeOpenCards(): String = "{\"tasks\":$tasks,\"files\":$files}"

/** Web's open flags; anything unreadable is "closed". Which card shows first is not kept (web neither). */
internal fun decodeConversationSideCardsOpen(raw: String?): ConversationSideCardsState {
    val value =
        raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return ConversationSideCardsState()
    fun flag(name: String): Boolean = (value[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    return ConversationSideCardsState(tasks = flag("tasks"), files = flag("files"))
}

/**
 * The person's open cards, kept across app restarts per person ([bindOwner]). Back on a phone
 * closes every open card at once, as web's `closeOpenCards`.
 */
internal class ConversationSideCardsStore(
    private val preferences: () -> SharedPreferenceStore? = { null },
) {
    private val mutableState = MutableStateFlow(ConversationSideCardsState())
    val state: StateFlow<ConversationSideCardsState> = mutableState.asStateFlow()
    private var ownerKey: String? = null
    private val store: SharedPreferenceStore? by lazy { runCatching(preferences).getOrNull() }

    /**
     * The signed-in person ([messageBodyFontSizeOwnerKey]: environment + user): loads their cards
     * once when the person changes; later changes are saved under them. Null (no session yet) keeps
     * what is shown.
     */
    fun bindOwner(ownerKey: String?) {
        if (ownerKey == null || ownerKey == this.ownerKey) return
        this.ownerKey = ownerKey
        val stored = runCatching { store?.getString(conversationSideCardsOpenKey(ownerKey)) }.getOrNull()
        mutableState.value = decodeConversationSideCardsOpen(stored)
    }

    fun toggle(card: ConversationSideCard) = change { it.toggled(card) }

    fun open(card: ConversationSideCard) = change { it.withOpen(card, true) }

    fun close(card: ConversationSideCard) = change { it.withOpen(card, false) }

    fun focus(card: ConversationSideCard) = mutableState.update { it.focused(card) }

    fun closeAll() = change { ConversationSideCardsState() }

    private fun change(transform: (ConversationSideCardsState) -> ConversationSideCardsState) {
        val before = mutableState.value
        mutableState.update(transform)
        val after = mutableState.value
        val owner = ownerKey ?: return
        if (before.tasks == after.tasks && before.files == after.files) return
        runCatching { store?.putString(conversationSideCardsOpenKey(owner), after.encodeOpenCards()) }
    }

    companion object {
        /** App-level: the same cards in every conversation page, saved in the shared local preferences. */
        val App: ConversationSideCardsStore = ConversationSideCardsStore(preferences = { raftSharedPreferenceStore() })
    }
}

internal val LocalConversationSideCards = staticCompositionLocalOf { ConversationSideCardsStore.App }

/** Cards replace the tab row only with the flag on, on a page that has the tab row. */
internal fun conversationSideCardsEnabled(
    taskV2Enabled: Boolean,
    showModeTabs: Boolean,
    assistantPanelEmbed: Boolean,
): Boolean = taskV2Enabled && showModeTabs && !assistantPanelEmbed

/** The card an old Tasks / Files tab link (route `selectedTab`, `?chatTab=`) opens with the flag on. */
internal fun RaftThreadTab.conversationSideCard(): ConversationSideCard? =
    when (this) {
        RaftThreadTab.Chat -> null
        RaftThreadTab.Tasks -> ConversationSideCard.Tasks
        RaftThreadTab.Files -> ConversationSideCard.Files
    }

internal fun ConversationSideCard.toThreadTab(): RaftThreadTab =
    when (this) {
        ConversationSideCard.Tasks -> RaftThreadTab.Tasks
        ConversationSideCard.Files -> RaftThreadTab.Files
    }

/**
 * The open card covering the conversation under its header (web ChannelSideCard, `overlay`):
 * with both open, the Tasks / Files switch on top; then the card's 56dp head with its title;
 * then its body. The conversation's header Back closes the cards, so the head draws none of its
 * own unless [onBack] is given (no header Back: a split's detail pane).
 *
 * On iOS the page's back swipe closes the cards like Back does ([onSwipeClose]; web closes the
 * card): the card slides away with the finger and the conversation shows again, instead of the
 * swipe doing nothing while the open card blocks the page pop. Android's system back gesture
 * already reaches the page's back handler, so nothing changes there.
 */
@Composable
internal fun ConversationSideCardsCover(
    cards: ConversationSideCardsState,
    onPick: (ConversationSideCard) -> Unit,
    onBack: (() -> Unit)?,
    onSwipeClose: () -> Unit,
    modifier: Modifier = Modifier,
    tasksContent: @Composable () -> Unit,
    filesContent: @Composable () -> Unit,
) {
    val shown = cards.shown ?: return
    val swipeClose = rememberRaftBackSwipeDismiss(onDismiss = onSwipeClose)
    Column(
        modifier
            .then(swipeClose)
            .fillMaxSize()
            .background(RaftColor.Canvas)
            // The conversation under the cover takes no touches.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        if (cards.openCards.size > 1) ConversationSideCardsSwitch(cards.openCards, shown, onPick)
        RaftPanelBackHeader(title = shown.title(), subtitle = null, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (shown) {
                ConversationSideCard.Tasks -> tasksContent()
                ConversationSideCard.Files -> filesContent()
            }
        }
    }
}

/** The Tasks / Files switch over one card at a time (web `channel-side-switch`): 48dp, a seam under it. */
@Composable
private fun ConversationSideCardsSwitch(
    open: List<ConversationSideCard>,
    shown: ConversationSideCard,
    onPick: (ConversationSideCard) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        RaftTabs {
            open.forEach { card ->
                RaftTab(
                    text = card.title(),
                    selected = card == shown,
                    onClick = { onPick(card) },
                    icon = { RaftContentLucideIcon(card.icon) },
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderSeam))
}

internal val ConversationSideCard.icon: RaftLucideIconName
    get() =
        when (this) {
            ConversationSideCard.Tasks -> RaftLucideIconName.ListTodo
            ConversationSideCard.Files -> RaftLucideIconName.Paperclip
        }

@Composable
private fun ConversationSideCard.title(): String =
    stringResource(
        when (this) {
            ConversationSideCard.Tasks -> Res.string.thread_tab_tasks
            ConversationSideCard.Files -> Res.string.thread_tab_files
        }
    )

// ---------------------------------------------------------------- wide: the cards' column in the pane grid

/**
 * The cards stand beside the conversation (web `beside`) on a wide window ([DualPaneMinimumWidthDp];
 * web: a desktop viewport, 768) when the page leaves the conversation its 360 next to the column's
 * 280 (web `placeSections`: `mins(columns) <= room - SIDE_CARDS_CHAT_MIN_WIDTH`). Otherwise an open
 * card covers the conversation ([ConversationSideCardsCover]), as on a phone.
 */
internal fun conversationSideCardsBeside(windowWidthDp: Float, pageWidthDp: Float): Boolean =
    windowWidthDp >= DualPaneMinimumWidthDp &&
        pageWidthDp >= RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP + RaftPaneGridColumns.SideCards.minWidthDp

/**
 * The same in the workspace's pane grid (web `placeSections`, #189): on a wide window
 * ([DualPaneMinimumWidthDp]; web: a desktop viewport, 768) the Tasks / Files column always stands
 * beside the conversation, and the grid's row plan makes room for it: every column gives way in
 * proportion, then clips down to 160, then the rightmost become tabs. Nothing covers the
 * conversation there.
 */
internal fun conversationSideCardsBesideInGrid(windowWidthDp: Float): Boolean = windowWidthDp >= DualPaneMinimumWidthDp

/** On screen: beside the conversation every open card is; covering it, the one shown. */
internal fun sideCardShowing(cards: ConversationSideCardsState, beside: Boolean, card: ConversationSideCard): Boolean =
    if (beside) cards.isOpen(card) else card == cards.shown

/** The cards' cell ids in the pane grid: web's section names, which key their folds and split. */
internal val ConversationSideCard.paneCellId: String
    get() =
        when (this) {
            ConversationSideCard.Tasks -> "tasks"
            ConversationSideCard.Files -> "files"
        }

/**
 * Task v2 on a wide page (web ChannelSideArea `beside`, ChannelSideCard): the open cards as the
 * pane grid's Tasks / Files column ([RaftPaneGrid]), Tasks over Files (web's default layout), each
 * a [RaftPaneGridCard] with its collapse chevron (where both share the column), title and ×. The
 * grid owns the column's width, the split and the folds; a column too short for both shows one at
 * a time under the Tasks / Files switch (web `sideCardsOneAtATime`).
 */
@Composable
internal fun conversationSideCardsColumn(
    cards: ConversationSideCardsState,
    onPick: (ConversationSideCard) -> Unit,
    onClose: (ConversationSideCard) -> Unit,
    tasksContent: @Composable () -> Unit,
    filesContent: @Composable () -> Unit,
): RaftPaneGridColumn? {
    val open = cards.openCards
    if (open.isEmpty()) return null
    val titles = ConversationSideCard.entries.associateWith { it.title() }
    val closeLabels =
        ConversationSideCard.entries.associateWith {
            stringResource(if (it == ConversationSideCard.Tasks) Res.string.thread_side_card_tasks_close else Res.string.thread_side_card_files_close)
        }
    val shown = cards.shown ?: open.first()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentTasks by rememberUpdatedState(tasksContent)
    val currentFiles by rememberUpdatedState(filesContent)
    return RaftPaneGridColumn(
        bounds = RaftPaneGridColumns.SideCards,
        resizeLabel =
            stringResource(
                Res.string.thread_side_card_resize_column,
                if (open.size > 1) stringResource(Res.string.thread_side_card_tasks_and_files) else titles.getValue(open.first()),
            ),
        splitLabel = stringResource(Res.string.thread_side_card_resize_split),
        cells =
            open.map { card ->
                RaftPaneGridCell(
                    id = card.paneCellId,
                    // Web `defaultShares(["tasks", "files"])`: 60/40.
                    defaultShare = if (card == ConversationSideCard.Tasks) 0.6f else 0.4f,
                ) {
                    RaftPaneGridCard(
                        title = titles.getValue(card),
                        closeLabel = closeLabels.getValue(card),
                        onClose = { currentOnClose(card) },
                    ) {
                        if (card == ConversationSideCard.Tasks) currentTasks() else currentFiles()
                    }
                }
            },
        shortShownCell = shown.paneCellId,
        shortSwitch = { ConversationSideCardsSwitch(open, shown, onPick) },
        // Its tab where the row is short of 160 per column (#189 rule 4): web RowTabs joins its sections' titles.
        tabLabel = open.joinToString(" · ") { titles.getValue(it) },
    )
}
