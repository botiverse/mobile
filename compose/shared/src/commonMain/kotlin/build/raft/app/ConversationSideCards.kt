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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * The person's open cards for the app's lifetime (web keeps them in localStorage). Back on a
 * phone closes every open card at once, as web's `closeOpenCards`.
 */
internal class ConversationSideCardsStore {
    private val mutableState = MutableStateFlow(ConversationSideCardsState())
    val state: StateFlow<ConversationSideCardsState> = mutableState.asStateFlow()

    fun toggle(card: ConversationSideCard) = mutableState.update { it.toggled(card) }

    fun open(card: ConversationSideCard) = mutableState.update { it.withOpen(card, true) }

    fun close(card: ConversationSideCard) = mutableState.update { it.withOpen(card, false) }

    fun focus(card: ConversationSideCard) = mutableState.update { it.focused(card) }

    fun closeAll() = mutableState.update { ConversationSideCardsState() }

    companion object {
        /** App-level: the same cards in every conversation page. */
        val App: ConversationSideCardsStore = ConversationSideCardsStore()
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
 */
@Composable
internal fun ConversationSideCardsCover(
    cards: ConversationSideCardsState,
    onPick: (ConversationSideCard) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tasksContent: @Composable () -> Unit,
    filesContent: @Composable () -> Unit,
) {
    val shown = cards.shown ?: return
    Column(
        modifier
            .fillMaxSize()
            .background(RaftColor.Canvas)
            // The conversation under the cover takes no touches.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        val open = cards.openCards
        if (open.size > 1) {
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
        RaftPanelBackHeader(title = shown.title(), subtitle = null, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (shown) {
                ConversationSideCard.Tasks -> tasksContent()
                ConversationSideCard.Files -> filesContent()
            }
        }
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
