package build.raft.app

import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.app.resources.*
import build.raft.app.components.RaftIconSpinner
import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftRadio
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSaveStatus
import build.raft.designsystem.components.RaftSaveStatusDefaults
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftTextField
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftSwitch
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.data.SharedSettingsActionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
internal data class RaftSharedSettingsPayload(
    val instanceId: String = "",
    val title: String = "",
    val detail: String = "",
    val heroIconName: RaftLucideIconName = RaftLucideIconName.Settings,
    val heroLabel: String = "",
    val sections: List<RaftSharedSettingsSection> = emptyList()
)

@Serializable
internal data class RaftSharedSettingsSection(
    val title: String = "",
    val rows: List<RaftSharedSettingsRow> = emptyList()
)

@Serializable
internal data class RaftSharedSettingsRow(
    val key: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Settings,
    val title: String = "",
    val detail: String = "",
    val value: String = "",
    val status: String = "",
    val enabled: Boolean = false,
    val toggleValue: Boolean? = null,
    val supportingDetail: String = "",
    val badgeLabel: String = "",
    /** Still loading: a spinner in the icon tile instead of [iconName]. */
    val loading: Boolean = false,
    /** When non-blank and a copy handler is wired, long-pressing the row copies this value. */
    val copyValue: String = "",
    /** The row takes people to another site: it ends in ↗ (raft-ui 0.26, #758). */
    val external: Boolean = false,
    /**
     * A hidden tap target on a row that does not look like a control (About's version row: 7 taps
     * open Developer): taps reach the action with no press wash, the row keeps its [enabled] look.
     */
    val hiddenTap: Boolean = false
)

/** A settings row's "Beta" (or similar) mark: the raft-ui solid `primary` Badge. */
@Composable
internal fun SettingsConnectedAppsBadge(label: String) {
    RaftBadge(text = label, variant = RaftBadgeVariant.Primary)
}

@Composable
internal fun SettingsLoadStateCard(
    iconName: RaftLucideIconName,
    title: String,
    detail: String,
    retryLabel: String?,
    onRetry: () -> Unit,
    // Still loading: a spinner in place of [iconName].
    loading: Boolean = false,
    // The failed request's trace id (ScreenError/PermissionBlocker.traceId), shown with a copy action.
    traceId: String? = null,
) {
    SettingsFormSectionCard(iconName = iconName, title = title, loading = loading) {
        SettingsFormDetailText(detail)
        RaftTraceIdNote(traceId)
        retryLabel?.let {
            RaftButton(
                text = it,
                onClick = onRetry,
                size = RaftButtonSize.Md,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
            )
        }
    }
}

@Composable
private fun SettingsServerSectionHeader(
    iconName: RaftLucideIconName,
    title: String,
    loading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            RaftIconSpinner(size = 16.dp, color = RaftColor.TextBlack)
        } else {
            RaftLucideIcon(iconName, modifier = Modifier.size(16.dp), color = RaftColor.TextBlackArgb)
        }
        Text(title, style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
    }
}

@Composable
internal fun SettingsFormSectionCard(
    iconName: RaftLucideIconName,
    title: String,
    loading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsFormSection(iconName = iconName, title = title, loading = loading) {
        RaftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/**
 * Web `SectionHeader className="mb-3"` over a section's own cards ([content], 12dp apart): the
 * header without the one card [SettingsFormSectionCard] adds.
 */
@Composable
internal fun SettingsFormSection(
    iconName: RaftLucideIconName,
    title: String,
    loading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsServerSectionHeader(iconName = iconName, title = title, loading = loading)
        content()
    }
}

@Composable
internal fun SettingsSegmentedControlField(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    detail: String? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    /** A choice that applies at once: its raft-ui SaveStatus line, 4dp under the control, height kept while empty. */
    saveStatus: RaftSaveStatusState? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.takeIf { it.isNotBlank() }?.let {
            RaftFieldLabel(text = it, size = RaftFieldLabelSize.Sm)
        }
        detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        Column(verticalArrangement = Arrangement.spacedBy(RaftSaveStatusDefaults.Gap)) {
            SegmentedControl(
                selectedValue = selectedValue,
                options = options,
                onValueSelected = onValueSelected,
                enabled = enabled,
                fillWidth = fillWidth
            )
            saveStatus?.let { RaftSaveStatus(it) }
        }
    }
}

@Composable
internal fun SettingsServerActionRow(
    title: String,
    detail: String,
    buttonLabel: String,
    buttonIcon: RaftLucideIconName,
    buttonVariant: RaftButtonVariant,
    enabled: Boolean,
    // Long-press copy of the title (raftCopyable): a name, an email, a link.
    copyText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, modifier = Modifier.raftCopyable(copyText), style = RaftType.Button.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
            Text(detail, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
        }
        RaftButton(
            text = buttonLabel,
            onClick = onClick,
            enabled = enabled,
            variant = buttonVariant,
            size = RaftButtonSize.Md,
            leadingIcon = { RaftSlotIcon(buttonIcon) }
        )
    }
}

@Composable
internal fun SettingsServerSelectRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // The design-system option card: the radio shows the pick, the whole card is the target.
    RaftCard(
        modifier = Modifier.fillMaxWidth(),
        variant = RaftCardVariant.Option,
        title = title,
        description = detail.takeIf { it.isNotBlank() },
        leading = { RaftRadio(selected = selected, onClick = null) },
        onClick = onClick,
        selected = selected,
        enabled = enabled
    )
}

/** A settings text field: raft-ui Field (`sm` label) around an Input. */
@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    required: Boolean = false,
    optional: Boolean = false
) {
    RaftField(
        label = label,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        required = required,
        optional = optional,
        labelSize = RaftFieldLabelSize.Sm
    ) {
        RaftTextField(value = value, onValueChange = onValueChange, placeholder = placeholder)
    }
}

/**
 * A settings field that picks one value: raft-ui Field + ComboboxSelect (an anchored list for
 * up to ten options, a searchable sheet for more, such as the timezones).
 */
@Composable
internal fun SettingsComboboxField(
    /** Null inside a section card whose title already names the choice. */
    label: String?,
    description: String,
    value: String,
    options: List<RaftComboboxOption<String>>,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    /** A choice that applies at once: its raft-ui SaveStatus line, under the control and before [description]. */
    saveStatus: RaftSaveStatusState? = null
) {
    RaftField(label = label, description = description, enabled = enabled, labelSize = RaftFieldLabelSize.Sm, saveStatus = saveStatus) {
        val noMatches = stringResource(Res.string.common_no_matches)
        RaftCombobox(
            value = value,
            options = options,
            onValueChange = onValueChange,
            emptyText = { noMatches },
            searchPlaceholder = stringResource(Res.string.thread_action_search)
        )
    }
}

@Composable
internal fun SettingsFormDetailText(text: String) {
    Text(text, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
}

@Composable
internal fun SettingsFormEmptyText(text: String) {
    Text(text, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
}

@Composable
internal fun SettingsFormErrorText(text: String) {
    Text(text, style = RaftType.Caption.copy(color = RaftColor.AccentRed), color = RaftColor.AccentRed)
}

@Composable
internal fun SettingsFormErrorText(actionState: SharedSettingsActionState) {
    actionState
        .userFacingErrorMessage(localizedErrorPresentationStrings())
        ?.let { SettingsFormErrorText(it) }
}

@Composable
internal fun SettingsFormDivider() {
    Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
}

@Composable
internal fun SharedSettingsSectionCard(
    section: RaftSharedSettingsSection,
    onCopyValue: ((String) -> Unit)? = null,
    onAction: (String, String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().raftCardSurface(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            // raft-ui section titles keep source case.
            section.title,
            style = RaftType.SectionLabel.copy(color = RaftColor.ForegroundHint, letterSpacing = 0.sp),
            color = RaftColor.ForegroundHint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        )
        section.rows.forEachIndexed { index, row ->
            SharedSettingsRow(row, showDivider = index > 0, onCopyValue = onCopyValue, onAction = onAction)
        }
    }
}

@Composable
private fun SharedSettingsRow(
    row: RaftSharedSettingsRow,
    showDivider: Boolean,
    onCopyValue: ((String) -> Unit)? = null,
    onAction: (String, String?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
        }
        // A row's copyValue copies on a long press on every settings page: the page's own
        // handler when it passes one, else the design-system copy (host pasteboard + "Copied").
        val copyAction = onCopyValue ?: rememberRaftCopyAction()
        val haptics = rememberRaftHaptics()
        val copyable = row.copyValue.isNotBlank()
        val gestureOwner =
            rememberRaftGestureOwner(
                surface = "settings-row",
                semanticIdentity = row.key,
                callbacks =
                    SharedSettingsRowGestureCallbacks(
                        row = row,
                        onCopyValue = { value ->
                            haptics.longPress()
                            copyAction(value)
                        },
                        onAction = onAction
                    )
            )
        val rowGestureModifier =
            if (copyable) {
                Modifier.raftGesturePointerInput(gestureOwner, row.copyValue, row.enabled) { owner ->
                    var gesture: RaftGestureSession<SharedSettingsRowGestureCallbacks>? = null
                    detectTapGestures(
                        onPress = {
                            gesture = owner.beginGesture()
                            tryAwaitRelease()
                        },
                        onTap = {
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                if (callbacks.row.enabled || callbacks.row.hiddenTap) {
                                    callbacks.onAction(
                                        callbacks.row.key,
                                        callbacks.row.value.takeIf { it.isNotBlank() }
                                    )
                                }
                            }
                        },
                        onLongPress = {
                            (gesture ?: owner.beginGesture()).currentCallbacksOrNull()?.let { callbacks ->
                                callbacks.onCopyValue?.invoke(callbacks.row.copyValue)
                            }
                        }
                    )
                }
            } else if (row.hiddenTap && !row.enabled) {
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onAction(row.key, row.value.takeIf { it.isNotBlank() })
                }
            } else {
                Modifier.clickable(enabled = row.enabled) { onAction(row.key, row.value.takeIf { it.isNotBlank() }) }
            }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RaftColor.Card)
                .then(rowGestureModifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(if (row.enabled) RaftColor.Info else RaftColor.EmptyStateGray, RoundedCornerShape(RaftAppRadius.Sticker)).border(RaftAppStroke.Control, if (row.enabled) RaftColor.StickerInk else RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Sticker)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                if (row.loading) {
                    RaftIconSpinner(size = 18.dp, color = if (row.enabled) RaftColor.StickerInk else RaftColor.TextBlack)
                } else {
                    RaftLucideIcon(
                        row.iconName,
                        modifier = Modifier.size(18.dp),
                        color = if (row.enabled) raftArgbHex(RaftColor.StickerInk) else RaftColor.TextBlackArgb
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = RaftType.Button.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    row.badgeLabel.takeIf { it.isNotBlank() }?.let {
                        SettingsConnectedAppsBadge(it)
                    }
                }
                row.detail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                row.supportingDetail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            row.status.takeIf { it.isNotBlank() }?.let {
                Text(it, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray, maxLines = 1)
            }
            if (row.external) {
                // raft-ui 0.26 (#758): a row that leaves Raft ends in ↗ in its trailing slot, in the
                // hint ink; decorative, the row's text says where it goes.
                RaftLucideIcon(RaftLucideIconName.ArrowUpRight, modifier = Modifier.size(16.dp), color = RaftColor.TextMutedGrayArgb)
            }
            row.toggleValue?.let { enabled ->
                RaftSwitch(checked = enabled, onCheckedChange = null, enabled = row.enabled)
            }
        }
    }
}

private data class SharedSettingsRowGestureCallbacks(
    val row: RaftSharedSettingsRow,
    val onCopyValue: ((String) -> Unit)?,
    val onAction: (String, String?) -> Unit
)

/**
 * Web `SettingsProfileCard` (Account and Server Profile): a card (p-4, 16dp stack) whose head is
 * the avatar in a 64dp box beside the title (headline) over a mono subtitle, then a separator and
 * the form ([content], 12dp apart).
 */
@Composable
internal fun SettingsProfileIdentityCard(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) { avatar() }
                Column(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RaftText(
                        title,
                        modifier = Modifier.raftCopyable(title),
                        style = RaftTheme.typography.headline,
                        color = RaftTheme.colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        RaftText(
                            subtitle,
                            modifier = Modifier.raftCopyable(subtitle),
                            style = RaftTheme.typography.monoCode,
                            color = RaftTheme.colors.foregroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                RaftSeparator()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Web read-only `PrefixedInput` / `SlugInput` (the username's "@", the server slug's "/"): the
 * design system's read-only field with its container prefix. Long-press copies [copyText].
 */
@Composable
internal fun SettingsReadOnlyPrefixedField(prefix: String, text: String, copyText: String? = text) {
    RaftReadOnlyField(text = text, prefix = prefix, copyText = copyText)
}
