package build.raft.app

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal object SlockColor {
    val BrandYellow = Color(0xFFFFD440)
    val AccentPink = Color(0xFFFE7DA8)
    val AccentCyan = Color(0xFF27CCF3)
    val AccentOrange = Color(0xFFF8A16F)
    val AccentLavender = Color(0xFFBBAFE6)
    val AccentLime = Color(0xFFA9D877)
    val AccentRed = Color(0xFFF97264)
    val AccentStone = Color(0xFFC0B9B1)
    val SurfaceWhite = Color(0xFFFFFFFF)
    val SurfaceCream = Color(0xFFFFFAEF)
    val AppBgLightBlue = Color(0xFFE6F8FF)
    val EmptyStateGray = Color(0xFFE7E7E7)
    val TextBlack = Color(0xFF000000)
    val TextMutedGray = Color(0xFF737373)
    val BorderBlack = Color(0xFF000000)
    val ShadowBlack = Color(0xFF141111)

    const val BrandYellowArgb = "#FFFFD440"
    const val SurfaceWhiteArgb = "#FFFFFFFF"
    const val TextBlackArgb = "#FF000000"
    const val TextMutedGrayArgb = "#FF737373"
    const val BorderBlackArgb = "#FF000000"
    const val ShadowBlackArgb = "#FF141111"
    const val AccentOrangeArgb = "#FFF8A16F"
}

internal object SlockSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

internal object SlockStroke {
    val Thin = 1.dp
    val Default = 2.dp
    val Strong = 3.dp
}

internal object SlockRadius {
    val None = 0.dp
    val Xs = 2.dp
    val Sm = 4.dp
}

internal object SlockSize {
    val MinTouchTarget = 48.dp
    val IconButton = 44.dp
    val HeaderIcon = 36.dp
    val PageHeaderHeight = 62.dp
    val PageHeaderContentHeight = PageHeaderHeight - SlockStroke.Default
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp
}

internal object SlockShadow {
    val Brutal = 4.dp
    val BrutalSmall = 2.dp
    val BrutalLarge = 6.dp
    val BrutalActive = 1.dp
}

// Kuikly serializes FontFamily as comma-joined font names WITHOUT weight info.
// A single FontFamily with multiple weights would always pick the first entry.
// We must use one FontFamily per weight and manually dispatch.
// See KuiklyTextExtension.toKuiklyFontFamily() for the serialization constraint.
private val SpaceGroteskBold = FontFamily(KuiklyFont("SpaceGrotesk-Bold", FontWeight.Bold, FontStyle.Normal))
private val SpaceGroteskSemiBold = FontFamily(KuiklyFont("SpaceGrotesk-SemiBold", FontWeight.SemiBold, FontStyle.Normal))
private val SpaceGroteskMedium = FontFamily(KuiklyFont("SpaceGrotesk-Medium", FontWeight.Medium, FontStyle.Normal))
private val SpaceGroteskRegular = FontFamily(KuiklyFont("SpaceGrotesk-Regular", FontWeight.Normal, FontStyle.Normal))
private val SpaceGroteskFamilies = setOf(
    SpaceGroteskBold,
    SpaceGroteskSemiBold,
    SpaceGroteskMedium,
    SpaceGroteskRegular
)

private fun spaceGroteskFamily(fontWeight: FontWeight): FontFamily =
    when {
        fontWeight >= FontWeight.Bold -> SpaceGroteskBold
        fontWeight >= FontWeight.SemiBold -> SpaceGroteskSemiBold
        fontWeight >= FontWeight.Medium -> SpaceGroteskMedium
        else -> SpaceGroteskRegular
    }

internal fun slockFontFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    spaceGroteskFamily(fontWeight ?: FontWeight.Normal)

private fun TextStyle.withSlockRenderedFontWeight(): TextStyle =
    if (fontFamily in SpaceGroteskFamilies) {
        copy(fontWeight = FontWeight.Normal)
    } else {
        this
    }

private fun slockTextStyle(
    fontWeight: FontWeight,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit
): TextStyle =
    TextStyle(
        fontFamily = spaceGroteskFamily(fontWeight),
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing
    )

internal fun TextStyle.withSlockFontWeight(fontWeight: FontWeight): TextStyle =
    copy(fontFamily = spaceGroteskFamily(fontWeight), fontWeight = FontWeight.Normal)

internal fun slockSpanStyleForWeight(fontWeight: FontWeight): SpanStyle =
    SpanStyle(fontFamily = spaceGroteskFamily(fontWeight), fontWeight = FontWeight.Normal)

internal fun TextStyle.withResolvedSlockFontFamily(): TextStyle =
    when {
        fontFamily == null ->
            copy(fontFamily = slockFontFamilyForWeight(fontWeight), fontWeight = FontWeight.Normal)
        fontFamily in SpaceGroteskFamilies && fontWeight != null && fontWeight != FontWeight.Normal ->
            copy(fontFamily = slockFontFamilyForWeight(fontWeight), fontWeight = FontWeight.Normal)
        else ->
            withSlockRenderedFontWeight()
    }

internal object SlockType {
    val PageTitle = slockTextStyle(FontWeight.SemiBold, 20.sp, 24.sp, 0.sp)
    val SectionLabel = slockTextStyle(FontWeight.SemiBold, 12.sp, 16.sp, 1.2.sp)
    val Body = slockTextStyle(FontWeight.Normal, 14.sp, 20.sp, 0.sp)
    val Caption = slockTextStyle(FontWeight.Normal, 12.sp, 16.sp, 0.sp)
    val Button = slockTextStyle(FontWeight.Medium, 14.sp, 18.sp, 0.sp)
    val ButtonBold = slockTextStyle(FontWeight.Bold, 14.sp, 18.sp, 0.sp)
    val TabLabel = slockTextStyle(FontWeight.Bold, 10.sp, 12.sp, 0.sp)
    val Input = slockTextStyle(FontWeight.Normal, 14.sp, 20.sp, 0.sp)
}
