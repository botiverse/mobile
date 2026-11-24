package build.raft.app.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTraceIdNote
import build.raft.app.SettingsDetailShell
import build.raft.app.raftArgbHex
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardFooterText
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

// The Assistant page as web draws it (botiverse/slock `packages/web/src/assistant/AssistantRoute.tsx`
// `AssistantLoading`, `CreateAssistantView.tsx`, `AssistantConversation.tsx`): a pushed page on
// the phone with the panel head and Back, over a spinner, the Create card or the DM.

/** What the Assistant page's parts do; the route wires them (AssistantPage.kt). */
internal class AssistantPageActions(
    val onBack: () -> Unit,
    val onCreate: () -> Unit = {},
    /** The capacity banner's link: plans (Free) or Add seats (Pro), or the view-only plan page. */
    val onViewPlans: () -> Unit = {},
    /** The head's gear: the assistant's profile, where its owner's Assistant section is. */
    val onOpenSettings: () -> Unit = {},
)

/**
 * The page for [view] (Off draws the loading head: the route leaves for Home at once). [paused]
 * and the conversation body ([conversation]: the DM, or "Setting up your chat…") are the route's.
 */
@Composable
internal fun AssistantPageScreen(
    view: AssistantPageView,
    actions: AssistantPageActions,
    paused: Boolean = false,
    conversation: @Composable ColumnScope.(AssistantPageView.Conversation) -> Unit = {},
) {
    RaftDesignSystemTheme {
        when (view) {
            AssistantPageView.Loading, AssistantPageView.Off -> AssistantLoadingView(actions.onBack)
            is AssistantPageView.Create -> AssistantCreateView(view.form, actions)
            is AssistantPageView.Conversation -> AssistantConversationView(view, paused, actions, conversation)
        }
    }
}

/** Web `AssistantLoading`: the page's head and Back while it answers, a spinner in the middle. */
@Composable
private fun AssistantLoadingView(onBack: () -> Unit) {
    SettingsDetailShell(title = stringResource(Res.string.assistant_title), subtitle = "", onBack = onBack) {
        Box(
            Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(AssistantPageTags.Loading),
            contentAlignment = Alignment.Center,
        ) {
            RaftSpinner(contentDescription = stringResource(Res.string.assistant_loading))
        }
    }
}

/**
 * Web `CreateAssistantView`: the card "Create your assistant", what it can do (four checked
 * lines), Create, and the footer. A failure shows above the list: the generic warning, or the
 * server's limit message with its link to plans; both with the request's trace ID.
 */
@Composable
private fun AssistantCreateView(form: AssistantCreateForm, actions: AssistantPageActions) {
    val colors = RaftTheme.colors
    SettingsDetailShell(title = stringResource(Res.string.assistant_title), subtitle = "", onBack = actions.onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.layerCanvas)
                .verticalScroll(rememberScrollState())
                // Web ScrollArea `px-4 py-8`.
                .padding(horizontal = 16.dp, vertical = 32.dp)
                .testTag(AssistantPageTags.Create),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RaftCard(
                // Web `mx-auto w-full max-w-md`.
                modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
                title = stringResource(Res.string.assistant_create_title),
                description = stringResource(Res.string.assistant_create_description),
                footer = { RaftCardFooterText(stringResource(Res.string.assistant_create_footer)) },
            ) {
                // Web form `grid gap-5`.
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    form.failure?.let { AssistantCreateFailureBanner(it, actions.onViewPlans) }
                    AssistantCreateCapabilities()
                    RaftButton(
                        text = stringResource(if (form.submitting) Res.string.assistant_create_submitting else Res.string.assistant_create_submit),
                        onClick = actions.onCreate,
                        modifier = Modifier.fillMaxWidth().testTag(AssistantPageTags.CreateSubmit),
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                        enabled = !form.submitting,
                    )
                }
            }
        }
    }
}

/** Web `assistant.create.can*`: what the assistant does from the start, each line with a check. */
@Composable
private fun AssistantCreateCapabilities() {
    val lines =
        listOf(
            Res.string.assistant_create_can_read_channels,
            Res.string.assistant_create_can_read_dms,
            Res.string.assistant_create_can_message_you,
            Res.string.assistant_create_can_send_as_you,
        )
    // Web `ul grid gap-2 text-callout`, labelled "What your assistant can do".
    val label = stringResource(Res.string.assistant_create_can_label)
    Column(
        Modifier.semantics { this.contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Web `Check size={16} mt-0.5 text-success-strong`, on the text's first line.
                RaftGlyphIcon(
                    RaftGlyph.Check,
                    size = 16.dp,
                    modifier = Modifier.padding(top = 2.dp),
                    color = RaftTheme.colors.successStrong,
                )
                RaftText(stringResource(line), style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
            }
        }
    }
}

/**
 * The failure over the list (web `assistant-create-error` / `assistant-create-capacity`): a small
 * warning banner; the limit's carries the server's message and a strong "View plans" link.
 */
@Composable
private fun AssistantCreateFailureBanner(failure: AssistantCreateFailure, onViewPlans: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (failure) {
            is AssistantCreateFailure.Generic ->
                RaftBanner(
                    description = stringResource(Res.string.assistant_create_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.testTag(AssistantPageTags.CreateError),
                )
            is AssistantCreateFailure.Capacity -> {
                val message = failure.error.serverMessage.orEmpty()
                val viewPlans = stringResource(Res.string.assistant_create_view_plans)
                RaftBanner(
                    description =
                        buildAnnotatedString {
                            append(message)
                            append(" ")
                            appendRaftTextLink(viewPlans, onClick = onViewPlans)
                        },
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.testTag(AssistantPageTags.CreateCapacity),
                )
            }
        }
        RaftTraceIdNote(failure.error.traceId)
    }
}

/**
 * Web `AssistantConversation`: the head (Ottie's face, the name with the Paused chip, what it can
 * read, Back, and the gear to its settings), then the owner's DM with it.
 */
@Composable
private fun AssistantConversationView(
    view: AssistantPageView.Conversation,
    paused: Boolean,
    actions: AssistantPageActions,
    conversation: @Composable ColumnScope.(AssistantPageView.Conversation) -> Unit,
) {
    val assistant = view.assistant
    val subtitle =
        stringResource(if (assistant.readsDms == true) Res.string.assistant_header_meta_with_dms else Res.string.assistant_header_meta)
    val settingsLabel = stringResource(Res.string.assistant_settings_label)
    SettingsDetailShell(
        title = assistant.name,
        subtitle = subtitle,
        onBack = actions.onBack,
        titleContent = { AssistantConversationTitle(assistant.name, subtitle, paused) },
        trailingContent = {
            RaftIconButton(
                onClick = actions.onOpenSettings,
                tooltip = settingsLabel,
                modifier = Modifier.testTag(AssistantPageTags.Settings),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Lg,
            ) {
                RaftLucideIcon(RaftLucideIconName.Settings, Modifier.size(RaftTheme.controls.barIconSize), color = raftArgbHex(LocalRaftContentColor.current))
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(AssistantPageTags.Conversation)) {
            conversation(view)
        }
    }
}

@Composable
private fun RowScope.AssistantConversationTitle(name: String, subtitle: String, paused: Boolean) {
    val colors = RaftTheme.colors
    AssistantHeadFace()
    Column(Modifier.weight(1f).padding(start = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RaftText(
                name,
                Modifier.weight(1f, fill = false).semantics { heading() },
                style = RaftTheme.typography.headline,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (paused) AssistantPausedChip()
        }
        // Web AssistantSubtitle: one line, cut with an ellipsis where it does not fit.
        RaftText(
            subtitle,
            Modifier.testTag(AssistantPageTags.Subtitle),
            style = RaftTheme.typography.caption,
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Web `AssistantChatSettingUp`: the assistant exists, its DM not yet; the store asks again with backoff. */
@Composable
internal fun ColumnScope.AssistantChatSettingUp() {
    Row(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).testTag(AssistantPageTags.SettingUp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftSpinner()
        RaftText(
            stringResource(Res.string.assistant_setting_up_chat),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Web `data-testid`s of the page. */
internal object AssistantPageTags {
    const val Loading = "assistant-loading"
    const val Create = "assistant-create"
    const val CreateSubmit = "assistant-create-submit"
    const val CreateError = "assistant-create-error"
    const val CreateCapacity = "assistant-create-capacity"
    const val Conversation = "assistant-conversation"
    const val Subtitle = "assistant-subtitle"
    const val Settings = "assistant-settings-trigger"
    const val SettingUp = "assistant-chat-setting-up"
}
