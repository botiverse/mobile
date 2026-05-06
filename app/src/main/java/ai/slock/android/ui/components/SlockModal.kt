package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockModalActionStyle {
    Primary,
    Outline,
    Warning,
    Danger
}

data class SlockModalAction(
    val text: String,
    val onClick: () -> Unit,
    val style: SlockModalActionStyle = SlockModalActionStyle.Primary,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val icon: ImageVector? = null
)

@Composable
fun SlockModal(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dismissOnScrimClick: Boolean = true,
    errorText: String? = null,
    primaryAction: SlockModalAction? = null,
    secondaryAction: SlockModalAction? = null,
    dangerActions: List<SlockModalAction> = emptyList(),
    content: @Composable () -> Unit
) {
    val panelInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.TextBlack.copy(alpha = 0.42f))
            .clickable(
                enabled = enabled && dismissOnScrimClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
            .padding(SlockSpacing.Md),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = SlockShadow.Default.offsetX, bottom = SlockShadow.Default.offsetY)
                .slockHardShadow(SlockShadow.Default)
                .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
                .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                .clickable(
                    indication = null,
                    interactionSource = panelInteractionSource,
                    onClick = {}
                )
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SlockModalHeader(
                title = title,
                onDismiss = onDismiss,
                enabled = enabled
            )
            Box(modifier = Modifier.alpha(if (enabled) 1f else 0.52f)) {
                content()
            }
            if (!errorText.isNullOrBlank()) {
                SlockModalNotice(text = errorText)
            }
            if (secondaryAction != null || primaryAction != null) {
                SlockModalActions(
                    secondaryAction = secondaryAction,
                    primaryAction = primaryAction
                )
            }
            if (dangerActions.isNotEmpty()) {
                SlockModalDangerZone(actions = dangerActions)
            }
        }
    }
}

@Composable
private fun SlockModalHeader(
    title: String,
    onDismiss: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Text(
            text = title,
            style = SlockType.PageTitle,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SlockIconSquareButton(
            icon = SlockIcons.X,
            contentDescription = "Close",
            onClick = onDismiss,
            enabled = enabled,
            modifier = Modifier.size(38.dp)
        )
    }
}

@Composable
private fun SlockModalNotice(text: String) {
    Text(
        text = text,
        style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.AccentPink)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
    )
}

@Composable
private fun SlockModalActions(
    secondaryAction: SlockModalAction?,
    primaryAction: SlockModalAction?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (secondaryAction != null) {
            SlockModalActionButton(
                action = secondaryAction.copy(style = SlockModalActionStyle.Outline)
            )
        }
        if (primaryAction != null) {
            SlockModalActionButton(
                action = primaryAction
            )
        }
    }
}

@Composable
fun SlockModalFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false
) {
    Text(
        text = buildAnnotatedString {
            append(text)
            if (required) {
                append(" ")
                withStyle(SpanStyle(color = SlockColor.AccentPink)) {
                    append("*")
                }
            } else if (optional) {
                append(" ")
                withStyle(SpanStyle(color = SlockColor.TextMutedGray)) {
                    append("(optional)")
                }
            }
        },
        style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
        color = SlockColor.TextBlack,
        modifier = modifier.padding(bottom = SlockSpacing.Xs)
    )
}

@Composable
fun SlockModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    required: Boolean = false,
    optional: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 4
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SlockModalFieldLabel(
            text = label,
            required = required,
            optional = optional
        )
        SlockTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = enabled,
            isError = isError,
            errorText = errorText,
            reserveErrorSpace = false,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines
        )
    }
}

@Composable
private fun SlockModalDangerZone(actions: List<SlockModalAction>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(top = SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = "DANGER ZONE",
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(horizontal = SlockSpacing.Md)
        )
        actions.forEach { action ->
            SlockModalActionButton(
                action = action,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SlockSpacing.Md)
            )
        }
        Box(modifier = Modifier.size(SlockSpacing.Xs))
    }
}

@Composable
private fun SlockModalActionButton(
    action: SlockModalAction,
    modifier: Modifier = Modifier
) {
    SlockButton(
        text = action.text,
        onClick = action.onClick,
        modifier = modifier,
        variant = action.style.buttonVariant,
        enabled = action.enabled,
        loading = action.loading,
        leadingContent = action.icon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = action.style.contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

private val SlockModalActionStyle.buttonVariant: SlockButtonVariant
    get() = when (this) {
        SlockModalActionStyle.Primary -> SlockButtonVariant.Pink
        SlockModalActionStyle.Outline -> SlockButtonVariant.Outline
        SlockModalActionStyle.Warning -> SlockButtonVariant.Orange
        SlockModalActionStyle.Danger -> SlockButtonVariant.Red
    }

private val SlockModalActionStyle.contentColor: Color
    get() = when (this) {
        else -> SlockColor.TextBlack
    }

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SlockModalDefaultPreview() {
    SlockTheme {
        SlockModal(
            title = "Edit channel",
            onDismiss = {},
            secondaryAction = SlockModalAction("Cancel", {}),
            primaryAction = SlockModalAction("Save", {}),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    SlockModalTextField(
                        value = "onboarding-artin",
                        onValueChange = {},
                        label = "Name",
                        placeholder = "channel-name",
                        required = true
                    )
                    SlockModalTextField(
                        value = "Plan, review, and ship Android onboarding work.",
                        onValueChange = {},
                        label = "Description",
                        placeholder = "Describe this channel",
                        optional = true,
                        singleLine = false,
                        minLines = 4,
                        maxLines = 5
                    )
                }
            }
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SlockModalStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SlockModal(
                title = "Invite member",
                onDismiss = {},
                errorText = "Email address is required before sending an invite.",
                secondaryAction = SlockModalAction("Cancel", {}),
                primaryAction = SlockModalAction("Sending", {}, loading = true),
                content = {
                    SlockModalTextField(
                        value = "",
                        onValueChange = {},
                        label = "Email",
                        placeholder = "name@company.com",
                        required = true,
                        isError = true,
                        errorText = "Email is required"
                    )
                }
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SlockModalDangerPreview() {
    SlockTheme {
        SlockModal(
            title = "Archive channel",
            onDismiss = {},
            secondaryAction = SlockModalAction("Cancel", {}),
            primaryAction = SlockModalAction("Confirm", {}, enabled = false),
            dangerActions = listOf(
                SlockModalAction(
                    text = "Archive channel",
                    onClick = {},
                    style = SlockModalActionStyle.Warning,
                    icon = SlockIcons.Archive
                ),
                SlockModalAction(
                    text = "Delete forever",
                    onClick = {},
                    style = SlockModalActionStyle.Danger,
                    icon = SlockIcons.Trash
                )
            ),
            content = {
                Text(
                    text = "This action changes channel visibility for every member. Destructive confirmation requires an explicit follow-up.",
                    style = SlockType.Body,
                    color = SlockColor.TextBlack
                )
            }
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SlockModalNoPermissionPreview() {
    SlockTheme {
        SlockModal(
            title = "Change workspace",
            onDismiss = {},
            enabled = false,
            errorText = "You do not have permission to change workspace settings.",
            secondaryAction = SlockModalAction("Cancel", {}, enabled = false),
            primaryAction = SlockModalAction("Save", {}, enabled = false),
            content = {
                SlockModalTextField(
                    value = "Slock Android",
                    onValueChange = {},
                    label = "Workspace name",
                    placeholder = "Workspace",
                    enabled = false,
                    required = true
                )
            }
        )
    }
}
