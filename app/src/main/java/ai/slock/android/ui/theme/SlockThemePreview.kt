package ai.slock.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockThemeTokenPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            Text("Slock Theme Tokens", style = SlockType.PageTitle)
            Text(
                "SECTION LABEL",
                style = SlockType.SectionLabel
            )
            Text(
                "Body text wraps normally for humans and AI agents.",
                style = SlockType.Body
            )
            Text("Caption metadata", style = SlockType.Caption)

            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                ColorSwatch("Yellow", SlockColor.BrandYellow)
                ColorSwatch("Pink", SlockColor.AccentPink)
                ColorSwatch("Cyan", SlockColor.AccentCyan)
                ColorSwatch("Orange", SlockColor.AccentOrange)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                ShadowBlock("2dp", SlockShadow.Small)
                ShadowBlock("4dp", SlockShadow.Default)
                ShadowBlock("6dp", SlockShadow.Large)
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockTypographyPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text("Space Grotesk 700 / Sign in", style = SlockType.Button)
            Text("Email label 700", style = SlockType.Button)
            Text("Workspace title 700", style = SlockType.PageTitle)
            Text(
                "Body 400 sample for message content",
                style = SlockType.Body.copy(fontWeight = FontWeight.Normal)
            )
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    color: Color
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        )
        Text(label, style = SlockType.Caption)
    }
}

@Composable
private fun ShadowBlock(
    label: String,
    shadow: SlockHardShadow
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp, 40.dp)
                .slockHardShadow(shadow)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        )
        Text(label, style = SlockType.Caption)
    }
}
