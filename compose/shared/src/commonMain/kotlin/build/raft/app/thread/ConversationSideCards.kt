package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.DualPaneMinimumWidthDp
import build.raft.app.RAFT_PANE_GRID_MAIN_MIN_WIDTH_DP
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftContentLucideIcon
import build.raft.app.RaftIncomingNavigationSignal
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftPaneGridCard
import build.raft.app.RaftPaneGridCell
import build.raft.app.RaftPaneGridColumn
import build.raft.app.RaftPaneGridColumns
import build.raft.app.RaftPanelBackHeader
import build.raft.app.resources.*
import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.raftPaneGridFitHead
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.gesture.rememberRaftBackSwipeDismiss
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Task v2 (web ChatPanel / ChannelSideArea, raft-design
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

    /**
     * Something is about to open ([RaftIncomingNavigationSignal]): where the cards cover the
     * conversation ([coverConversation], a phone), open cards close; true when they were open.
     * Beside the conversation (a wide page) they stay.
     */
    fun closeForIncomingNavigation(coverConversation: Boolean): Boolean {
        if (!coverConversation || !state.value.anyOpen) return false
        closeAll()
        return true
    }

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

/**
 * The cards are one setting for the person, so on a phone a conversation opened while they are
 * open showed them over it: a message link, a notification, banner or shortcut open landed under
 * the cover, its message anchored but hidden, and into the same conversation nothing seemed to
 * happen (the open was skipped as "already the top page"). Where the cards cover the conversation
 * ([coverConversation]):
 * - an incoming open ([signal], fired by [RaftShellNavigationIntake] before it opens) closes them
 *   and reports the cover, so an open into this conversation still goes through to its message;
 * - a page opened at a message ([focusedMessageId], e.g. at cold start, before any page could
 *   hear the signal) closes them once for that message, as soon as they cover it (also when the
 *   page first reads as wide and then measures narrow).
 * Beside the conversation (a wide page) the cards stay open.
 */
@Composable
internal fun ConversationSideCardsIncomingNavigationEffect(
    store: ConversationSideCardsStore,
    signal: RaftIncomingNavigationSignal?,
    coverConversation: Boolean,
    focusedMessageId: String?,
) {
    val cover by rememberUpdatedState(coverConversation)
    DisposableEffect(store, signal) {
        val unregister = signal?.register { store.closeForIncomingNavigation(cover) }
        onDispose { unregister?.invoke() }
    }
    // Once per message: the page coming back (Back from a page over it) keeps the cards as left.
    // Handled only once the cards cover the page: until it measures, a page on its own has the
    // window's width, so a narrow pane in a wide window (a split's detail pane) first reads as
    // "beside" and closes the cover when it turns out narrow.
    var focusHandled by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(store, focusedMessageId, coverConversation) {
        val focused = focusedMessageId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (focused == focusHandled || !coverConversation) return@LaunchedEffect
        focusHandled = focused
        store.closeForIncomingNavigation(coverConversation = true)
    }
}

/** Cards replace the tab row on a page that has the tab row (not in the assistant's panel). */
internal fun conversationSideCardsEnabled(
    showModeTabs: Boolean,
    assistantPanelEmbed: Boolean,
): Boolean = showModeTabs && !assistantPanelEmbed

/** The card an old Tasks / Files tab link (route `selectedTab`, `?chatTab=`) opens. */
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
    // A phone/overlay cover sits under the conversation header and never steals the grid's row tabs.
    CompositionLocalProvider(LocalRaftPaneGridHeadTabs provides null) {
        Column(
            modifier
                .then(swipeClose)
                .fillMaxSize()
                .background(RaftColor.Canvas)
                // The conversation under the cover takes no touches.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        ) {
            if (cards.openCards.size > 1) ConversationSideCardsSwitch(cards.openCards, shown, onPick, inColumn = false)
            RaftPanelBackHeader(title = shown.title(), subtitle = null, onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (shown) {
                    ConversationSideCard.Tasks -> tasksContent()
                    ConversationSideCard.Files -> filesContent()
                }
            }
        }
    }
}

internal const val CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG = "conversationSideCardsSwitch"

/**
 * The Tasks / Files switch over one card at a time (web `channel-side-switch`).
 *
 * In a wide split column (`shortSwitch`, [inColumn] = true), it sits at the top of the column
 * beside the conversation header, so the shell owns its height ([RaftTheme.controls.headHeight]:
 * 56dp touch, 44dp fine pointer) with the bottom seam inside that height and `.raftPaneGridFitHead()`
 * so the first bottom hairline aligns across every visible column.
 *
 * In the phone overlay ([ConversationSideCardsCover], [inColumn] = false), it matches web
 * `h-12 md:pointer-fine:h-panel-header` (48dp touch, 44dp fine pointer, seam inside).
 */
@Composable
private fun ConversationSideCardsSwitch(
    open: List<ConversationSideCard>,
    shown: ConversationSideCard,
    onPick: (ConversationSideCard) -> Unit,
    inColumn: Boolean = false,
) {
    val headHeight = RaftTheme.controls.headHeight
    val barHeight = if (inColumn) headHeight else minOf(48.dp, headHeight)
    val contentHeight = (barHeight - RaftAppStroke.Thin).coerceAtLeast(0.dp)
    Box(
        modifier =
            Modifier
                .then(if (inColumn) Modifier.raftPaneGridFitHead() else Modifier)
                .fillMaxWidth()
                .height(barHeight)
                .background(RaftColor.Canvas)
                .testTag(CONVERSATION_SIDE_CARDS_SWITCH_TEST_TAG),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(contentHeight).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (inColumn) {
                RaftTabStrip(inHead = true) {
                    open.forEach { card ->
                        RaftTab(
                            text = card.title(),
                            selected = card == shown,
                            onClick = { onPick(card) },
                            icon = { RaftContentLucideIcon(card.icon) },
                        )
                    }
                }
            } else {
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
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam.compositeOver(RaftColor.Canvas))
                    .align(Alignment.BottomCenter),
        )
    }
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
                    RaftFocusRegionHost(region = RaftFocusRegion.SupportingPane, modifier = Modifier.fillMaxSize()) {
                        RaftPaneGridCard(
                            title = titles.getValue(card),
                            closeLabel = closeLabels.getValue(card),
                            onClose = { currentOnClose(card) },
                        ) {
                            if (card == ConversationSideCard.Tasks) currentTasks() else currentFiles()
                        }
                    }
                }
            },
        shortShownCell = shown.paneCellId,
        shortSwitch = { ConversationSideCardsSwitch(open, shown, onPick, inColumn = true) },
        // Its tab where the row is short of 160 per column (#189 rule 4): web RowTabs joins its sections' titles.
        tabLabel = open.joinToString(" · ") { titles.getValue(it) },
        // Each card draws the column head (RaftPaneGridCard): the row tabs stand in the top card's head.
        headCarriesTabs = true,
    )
}
