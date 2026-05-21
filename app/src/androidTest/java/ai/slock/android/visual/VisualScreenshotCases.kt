package ai.slock.android.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.components.SlockButton
import ai.slock.android.ui.components.SlockButtonVariant
import ai.slock.android.ui.components.SlockMemberKind
import ai.slock.android.ui.components.SlockMemberRow
import ai.slock.android.ui.components.SlockPresence
import ai.slock.android.ui.components.SlockSegmentOption
import ai.slock.android.ui.components.SlockTextField
import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

data class VisualScreenshotCaseSpec(
    val id: String,
    val title: String
)

object VisualScreenshotCases {
    const val CaseWidthDp = 342

    val All =
        listOf(
            VisualScreenshotCaseSpec("tokens.palette", "Shared Tokens"),
            VisualScreenshotCaseSpec("components.buttons.states", "Button States"),
            VisualScreenshotCaseSpec("components.input.states", "Input States"),
            VisualScreenshotCaseSpec("components.member-row.data", "Member Row Data")
        )

    fun require(id: String): VisualScreenshotCaseSpec =
        All.firstOrNull { it.id == id } ?: error("Unknown visual screenshot case: $id")
}

@Composable
fun VisualScreenshotCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
        SlockTheme {
            when (spec.id) {
                "tokens.palette" -> TokenPaletteCase(spec, modifier)
                "components.buttons.states" -> ButtonStatesCase(spec, modifier)
                "components.input.states" -> InputStatesCase(spec, modifier)
                "components.member-row.data" -> MemberRowDataCase(spec, modifier)
                else -> UnknownCase(spec, modifier)
            }
        }
    }
}

@Composable
private fun TokenPaletteCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        val swatches =
            listOf(
                "Yellow" to SlockColor.BrandYellow,
                "Pink" to SlockColor.AccentPink,
                "Lavender" to SlockColor.AccentLavender,
                "Cyan" to SlockColor.AccentCyan,
                "Orange" to SlockColor.AccentOrange,
                "Lime" to SlockColor.AccentLime,
                "Red" to SlockColor.AccentRed,
                "Stone" to SlockColor.EmptyStateGray,
                "Surface White" to SlockColor.SurfaceWhite,
                "Surface Cream" to SlockColor.SurfaceCream,
                "App Bg Light Blue" to SlockColor.AppBgLightBlue,
                "Empty State Gray" to SlockColor.EmptyStateGray,
                "Text Black" to SlockColor.TextBlack,
                "Text Muted Gray" to SlockColor.TextMutedGray,
                "Border Black" to SlockColor.BorderBlack,
                "Shadow Black" to SlockColor.ShadowBlack
            )
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
            swatches.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    row.forEach { (name, color) ->
                        TokenSwatch(name = name, color = color, modifier = Modifier.weight(1f))
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Md)
            ) {
                Text(
                    text = "Shadow",
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = "${SlockShadow.Default.offsetX.value.toInt()}dp ${SlockShadow.Default.offsetY.value.toInt()}dp ${SlockShadow.Default.color}",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ButtonStatesCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockButton(
                    text = "Primary",
                    onClick = {},
                    variant = SlockButtonVariant.Yellow,
                    leadingContent = { CaseIcon(LucideIcons.MessageSquare) }
                )
                SlockButton(
                    text = "Neutral",
                    onClick = {},
                    variant = SlockButtonVariant.Outline,
                    leadingContent = { CaseIcon(LucideIcons.Settings) }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                SlockButton(
                    text = "Accent",
                    onClick = {},
                    variant = SlockButtonVariant.Pink,
                    leadingContent = { CaseIcon(LucideIcons.BellRing) }
                )
                SlockButton(
                    text = "Disabled",
                    onClick = {},
                    enabled = false,
                    variant = SlockButtonVariant.Outline
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                SlockSegmentOption("Chat 12", selected = true, onSelected = {})
                SlockSegmentOption("Tasks 3", selected = false, onSelected = {})
                SlockSegmentOption("Saved", selected = false, enabled = false, onSelected = {})
            }
        }
    }
}

@Composable
private fun InputStatesCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockTextField(
                value = "",
                onValueChange = {},
                label = "Empty",
                placeholder = "Message title",
                reserveErrorSpace = false
            )
            SlockTextField(
                value = "Focused value",
                onValueChange = {},
                label = "Focused",
                forceFocused = true,
                reserveErrorSpace = false
            )
            SlockTextField(
                value = "https://",
                onValueChange = {},
                label = "Disabled / Error",
                enabled = false,
                isError = true,
                errorText = "Server URL is required",
                reserveErrorSpace = true
            )
            SlockTextField(
                value = "Composer draft with multiple lines\nand #design context.",
                onValueChange = {},
                label = "Multiline",
                singleLine = false,
                minLines = 3,
                maxLines = 4,
                reserveErrorSpace = false
            )
        }
    }
}

@Composable
private fun MemberRowDataCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
            SlockMemberRow(
                name = "Android Developer",
                role = "codex",
                kind = SlockMemberKind.Agent,
                presence = SlockPresence.Online,
                avatarLabel = "A",
                detail = "8 unread"
            )
            SlockMemberRow(
                name = "Artin",
                role = "artin@slock.test",
                kind = SlockMemberKind.Human,
                presence = SlockPresence.Unknown,
                avatarLabel = "A"
            )
            SlockMemberRow(
                name = "Product UX Designer",
                role = "reviewing components",
                kind = SlockMemberKind.Agent,
                presence = SlockPresence.Offline,
                avatarLabel = "P",
                detail = "draft"
            )
        }
    }
}

@Composable
private fun UnknownCase(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier
) {
    VisualCaseShell(spec, modifier) {
        Text(
            text = "No Android visual case registered for ${spec.id}.",
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentRed.copy(alpha = 0.2f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md)
        )
    }
}

@Composable
private fun VisualCaseShell(
    spec: VisualScreenshotCaseSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .width(VisualScreenshotCases.CaseWidthDp.dp)
            .wrapContentHeight()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        content()
    }
}

@Composable
private fun TokenSwatch(
    name: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(color)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        )
        Text(
            text = name,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(top = SlockSpacing.Xs)
        )
    }
}

@Composable
private fun CaseIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = SlockColor.TextBlack,
        modifier = Modifier.size(16.dp)
    )
}
