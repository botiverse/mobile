package build.raft.app

import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
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

// Colours that exist on Web come from the generated tokens (tools/design-tokens), so a Web
// palette change shows up as a regenerated diff instead of silent drift. The remaining
// literals have no Web token.
internal object SlockColor {
    val BrandYellow = RaftUiPalette.brutalYellow
    // Semantic alias for react `--color-soft-signal` (== brutal/brand yellow #FFD440),
    // used for the self-mention chip highlight so callsites read intent, not hue.
    val SoftSignal = RaftUiPalette.softSignal
    val AccentPink = RaftUiPalette.brutalPink
    val AccentCyan = RaftUiPalette.brutalCyan
    val AccentOrange = RaftUiPalette.brutalOrange
    val AccentLavender = RaftUiPalette.brutalLavender
    val AccentLime = RaftUiPalette.brutalLime
    val AccentRed = RaftUiPalette.brutalRed
    val AccentStone = RaftUiPalette.brutalStone
    // raft-ui `--warning` (#FF6900): the warning badge/banner fill. Distinct from AccentOrange.
    val StateWarningBase = RaftUiPalette.warning
    val SurfaceWhite = RaftUiPalette.white
    val SurfaceCream = RaftUiPalette.brutalCream
    val AppBgLightBlue = Color(0xFFE6F8FF)
    val EmptyStateGray = Color(0xFFE7E7E7)
    // Tailwind gray-400 (#99A1AF): the neutral / offline status-dot fill.
    // Not the same gray as EmptyStateGray (#E7E7E7).
    val StatusNeutralGray = RaftUiPalette.gray400
    // Web brutal `--foreground` (body text, #141110 — a warm near-black, not pure black).
    val TextBlack = RaftUiThemeColors.Brutal.foreground
    // Tailwind neutral-500.
    val TextMutedGray = RaftUiPalette.neutral500
    // Web brutal `--line` (#141110).
    val BorderBlack = RaftUiThemeColors.Brutal.line
    val ShadowBlack = RaftUiPalette.brutalBlack

    const val BrandYellowArgb = "#FFFFD440"
    const val SurfaceWhiteArgb = "#FFFFFFFF"
    // Must equal TextBlack; Kuikly string-colour callsites.
    const val TextBlackArgb = "#FF141110"
    const val TextMutedGrayArgb = "#FF737373"
    const val BorderBlackArgb = "#FF141110"
    const val ShadowBlackArgb = "#FF141111"
    const val AccentOrangeArgb = "#FFF8A16F"
    const val AccentRedArgb = "#FFF97264"
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

private fun FontFamily.encodedSpaceGroteskWeight(): FontWeight? =
    when (this) {
        SpaceGroteskBold -> FontWeight.Bold
        SpaceGroteskSemiBold -> FontWeight.SemiBold
        SpaceGroteskMedium -> FontWeight.Medium
        SpaceGroteskRegular -> FontWeight.Normal
        else -> null
    }

private fun renderedSpaceGroteskWeight(fontFamily: FontFamily, requestedWeight: FontWeight): FontWeight =
    if (slockPlatformPreservesEncodedFontWeightForFallback()) {
        fontFamily.encodedSpaceGroteskWeight() ?: requestedWeight
    } else {
        FontWeight.Normal
    }

// Space Mono mirrors the Space Grotesk wiring above: one FontFamily per weight,
// manually dispatched (Kuikly drops weight info from the serialized family name,
// so the weight must be encoded in the font name). React renders mono text
// (`font-mono`) in Space Mono, which ships only Regular (400) and Bold (700), so
// semibold-or-heavier maps to Bold and everything lighter maps to Regular.
private val SpaceMonoBold = FontFamily(KuiklyFont("SpaceMono-Bold", FontWeight.Bold, FontStyle.Normal))
private val SpaceMonoRegular = FontFamily(KuiklyFont("SpaceMono-Regular", FontWeight.Normal, FontStyle.Normal))

private fun spaceMonoFamily(fontWeight: FontWeight): FontFamily =
    when {
        fontWeight >= FontWeight.SemiBold -> SpaceMonoBold
        else -> SpaceMonoRegular
    }

internal fun slockMonoFamilyForWeight(fontWeight: FontWeight?): FontFamily =
    spaceMonoFamily(fontWeight ?: FontWeight.Normal)

// Convenience token for the common regular-weight mono callsites (task numbers,
// timestamps, counts, handles, codes). Use slockMonoFamilyForWeight() when a
// callsite needs bold mono.
internal val SlockMonoFamily: FontFamily = SpaceMonoRegular

private fun TextStyle.withSlockRenderedFontWeight(): TextStyle {
    val family = fontFamily?.takeIf { it in SpaceGroteskFamilies } ?: return this
    return copy(fontWeight = renderedSpaceGroteskWeight(family, fontWeight ?: FontWeight.Normal))
}

private fun slockTextStyle(
    fontWeight: FontWeight,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit
): TextStyle {
    val fontFamily = spaceGroteskFamily(fontWeight)
    return TextStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight),
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing
    )
}

internal fun TextStyle.withSlockFontWeight(fontWeight: FontWeight): TextStyle {
    val fontFamily = spaceGroteskFamily(fontWeight)
    return copy(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    )
}

internal fun slockSpanStyleForWeight(fontWeight: FontWeight): SpanStyle {
    val fontFamily = spaceGroteskFamily(fontWeight)
    return SpanStyle(
        fontFamily = fontFamily,
        fontWeight = renderedSpaceGroteskWeight(fontFamily, fontWeight)
    )
}

internal fun TextStyle.withResolvedSlockFontFamily(): TextStyle {
    val requestedWeight = fontWeight
    return when {
        fontFamily == null ->
            slockFontFamilyForWeight(requestedWeight).let { family ->
                copy(
                    fontFamily = family,
                    fontWeight = renderedSpaceGroteskWeight(family, requestedWeight ?: FontWeight.Normal)
                )
            }
        fontFamily in SpaceGroteskFamilies && requestedWeight != null && requestedWeight != FontWeight.Normal ->
            slockFontFamilyForWeight(requestedWeight).let { family ->
                copy(fontFamily = family, fontWeight = renderedSpaceGroteskWeight(family, requestedWeight))
            }
        else ->
            withSlockRenderedFontWeight()
    }
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
