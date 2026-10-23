package build.raft.app

import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.raftCopyable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalCard
import build.raft.app.components.RaftSwitchButton
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.raftCardSurface
import build.raft.app.components.raftControlSurface
import build.raft.app.data.SharedSettingsActionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
    /** When non-blank and a copy handler is wired, long-pressing the row copies this value. */
    val copyValue: String = ""
)

@Composable
internal fun SettingsConnectedAppsBadge(
    label: String,
    backgroundColor: Color
) {
    // Sticker fills keep ink in both modes; the neutral loading fill is themed.
    val ink = if (backgroundColor == RaftColor.EmptyStateGray) RaftColor.TextBlack else RaftColor.StickerInk
    Box(
        modifier =
            Modifier
                // raft-ui Badge: sticker radius, 1.5dp sticker-ink edge.
                .background(backgroundColor, RoundedCornerShape(RaftAppRadius.Sticker))
                .border(RaftAppStroke.Control, ink, RoundedCornerShape(RaftAppRadius.Sticker))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = RaftType.TabLabel.copy(color = ink),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SettingsLoadStateCard(
    iconName: RaftLucideIconName,
    title: String,
    detail: String,
    retryLabel: String?,
    onRetry: () -> Unit
) {
    SettingsFormSectionCard(iconName = iconName, title = title) {
        SettingsFormDetailText(detail)
        retryLabel?.let {
            RaftBrutalActionButton(
                label = it,
                onClick = onRetry,
                leadingIcon = RaftLucideIconName.RefreshCw,
                backgroundColor = RaftColor.SurfaceWhite
            )
        }
    }
}

@Composable
private fun SettingsServerSectionHeader(
    iconName: RaftLucideIconName,
    title: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            iconName,
            modifier = Modifier.size(16.dp),
            color = RaftColor.TextBlackArgb,
            spinning = iconName == RaftLucideIconName.Loader || iconName == RaftLucideIconName.LoaderCircle
        )
        Text(title, style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
    }
}

@Composable
internal fun SettingsFormSectionCard(
    iconName: RaftLucideIconName,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SettingsServerSectionHeader(iconName = iconName, title = title)
        RaftBrutalCard(
            contentPadding = 14.dp,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
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
    fillWidth: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.takeIf { it.isNotBlank() }?.let {
            RaftFieldLabel(text = it, size = RaftFieldLabelSize.Sm)
        }
        detail?.takeIf { it.isNotBlank() }?.let { SettingsFormDetailText(it) }
        SegmentedControl(
            selectedValue = selectedValue,
            options = options,
            onValueSelected = onValueSelected,
            enabled = enabled,
            fillWidth = fillWidth
        )
    }
}

@Composable
internal fun SettingsServerActionRow(
    title: String,
    detail: String,
    buttonLabel: String,
    buttonIcon: RaftLucideIconName,
    buttonColor: Color,
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
        RaftBrutalActionButton(
            label = buttonLabel,
            onClick = onClick,
            enabled = enabled,
            leadingIcon = buttonIcon,
            backgroundColor = buttonColor,
            minWidth = 108.dp
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
    // raft selection card: primary-soft with an ink edge when chosen, no offset shadow.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftControlSurface(
                        fill = if (selected) RaftColor.BrandSoft else RaftColor.Card,
                        border = if (selected) RaftColor.BorderBlack else RaftColor.BorderHairline,
                        radius = RaftAppRadius.Container,
                        borderWidth = if (selected) RaftAppStroke.Control else RaftAppStroke.Thin
                    )
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftLucideIcon(
                if (selected) RaftLucideIconName.CircleCheck else RaftLucideIconName.Circle,
                modifier = Modifier.size(16.dp),
                color = RaftColor.TextBlackArgb,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = RaftType.Button.copy(color = RaftColor.TextBlack), color = RaftColor.TextBlack)
                detail.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
                }
            }
        }
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
        val haptics = LocalHapticFeedback.current
        val copyable = row.copyValue.isNotBlank()
        val gestureOwner =
            rememberRaftGestureOwner(
                surface = "settings-row",
                semanticIdentity = row.key,
                callbacks =
                    SharedSettingsRowGestureCallbacks(
                        row = row,
                        onCopyValue = { value ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                                if (callbacks.row.enabled) {
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
                modifier = Modifier.size(34.dp).background(if (row.enabled) RaftColor.AccentCyan else RaftColor.EmptyStateGray, RoundedCornerShape(RaftAppRadius.Sticker)).border(RaftAppStroke.Control, if (row.enabled) RaftColor.StickerInk else RaftColor.BorderBlack, RoundedCornerShape(RaftAppRadius.Sticker)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                RaftLucideIcon(
                    row.iconName,
                    modifier = Modifier.size(18.dp),
                    color = if (row.enabled) raftArgbHex(RaftColor.StickerInk) else RaftColor.TextBlackArgb
                )
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
                        SettingsConnectedAppsBadge(it, RaftColor.BrandYellow)
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
            row.toggleValue?.let { enabled ->
                RaftSwitchButton(checked = enabled, enabled = row.enabled)
            }
        }
    }
}

private data class SharedSettingsRowGestureCallbacks(
    val row: RaftSharedSettingsRow,
    val onCopyValue: ((String) -> Unit)?,
    val onAction: (String, String?) -> Unit
)

internal fun build.raft.app.data.SharedSettingsActionState?.statusFor(action: String, fallback: String): String =
    when {
        this == null || this.action != action -> fallback
        inProgress -> "Loading"
        errorCode != null -> "Error"
        else -> "Saved"
    }
