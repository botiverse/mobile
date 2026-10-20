package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TokenPaletteVisualContent() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .height(620.dp)
                .background(SlockColor.SurfaceCream)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Shared Tokens", style = SlockType.PageTitle, color = SlockColor.TextBlack)
        TokenSwatchGrid()
        TokenShadowCard()
    }
}

@Composable
private fun TokenSwatchGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SharedTokenPalette.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { token ->
                    TokenSwatch(token, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TokenSwatch(token: SharedColorToken, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(68.dp)) {
        Box(Modifier.matchParentSize().padding(start = 2.dp, top = 2.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(token.color)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
            )
            Text(token.label, style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp).withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(token.hex, style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
        }
    }
}

@Composable
private fun TokenShadowCard() {
    Box(modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Box(Modifier.matchParentSize().padding(start = 4.dp, top = 4.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Shadow", style = SlockType.ButtonBold, color = SlockColor.TextBlack)
            Text("4px 4px 0px #141111", style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp), color = SlockColor.TextBlack.copy(alpha = 0.6f))
        }
    }
}

private data class SharedColorToken(val label: String, val hex: String, val color: Color)

private val SharedTokenPalette =
    listOf(
        SharedColorToken("Brutal Yellow", "#FFD440", SlockColor.BrandYellow),
        SharedColorToken("Brutal Pink", "#FE7DA8", SlockColor.AccentPink),
        SharedColorToken("Brutal Lavender", "#BBAFE6", SlockColor.AccentLavender),
        SharedColorToken("Brutal Cream", "#FFFAEF", SlockColor.SurfaceCream),
        SharedColorToken("Brutal Black", "#141111", SlockColor.ShadowBlack),
        SharedColorToken("Brutal Cyan", "#27CCF3", SlockColor.AccentCyan),
        SharedColorToken("Brutal Orange", "#F8A16F", SlockColor.AccentOrange),
        SharedColorToken("Brutal Lime", "#A9D877", SlockColor.AccentLime),
        SharedColorToken("Brutal Red", "#F97264", SlockColor.AccentRed),
        SharedColorToken("Brutal Stone", "#C0B9B1", SlockColor.AccentStone),
        SharedColorToken("Surface White", "#FFFFFF", SlockColor.SurfaceWhite),
        SharedColorToken("Surface Cream", "#FFFAEF", SlockColor.SurfaceCream),
        SharedColorToken("App Bg Light Blue", "#E6F8FF", SlockColor.AppBgLightBlue),
        SharedColorToken("Empty State Gray", "#E7E7E7", SlockColor.EmptyStateGray),
        SharedColorToken("Text Black", "#000000", SlockColor.TextBlack),
        SharedColorToken("Text Muted Gray", "#737373", SlockColor.TextMutedGray),
        SharedColorToken("Border Black", "#000000", SlockColor.BorderBlack),
        SharedColorToken("Shadow Black", "#141111", SlockColor.ShadowBlack)
    )
