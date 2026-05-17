package ai.slock.compose

import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
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
    val SurfaceWhite = Color(0xFFFFFFFF)
    val SurfaceCream = Color(0xFFFFFAEF)
    val AppBgLightBlue = Color(0xFFE6F8FF)
    val EmptyStateGray = Color(0xFFE7E7E7)
    val TextBlack = Color(0xFF000000)
    val TextMutedGray = Color(0xFF737373)
    val BorderBlack = Color(0xFF000000)
    val ShadowBlack = Color(0xFF141111)
}

internal object SlockSpacing {
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
    val PageHeaderCompactHeight = 48.dp
    val BottomBarHeight = 53.dp
}

private val SpaceGroteskRegular = FontFamily(KuiklyFont("space_grotesk_regular", FontWeight.Normal, FontStyle.Normal))
private val SpaceGroteskMedium = FontFamily(KuiklyFont("space_grotesk_medium", FontWeight.Medium, FontStyle.Normal))
private val SpaceGroteskSemiBold = FontFamily(KuiklyFont("space_grotesk_semibold", FontWeight.SemiBold, FontStyle.Normal))
private val SpaceGroteskBold = FontFamily(KuiklyFont("space_grotesk_bold", FontWeight.Bold, FontStyle.Normal))

internal object SlockType {
    val PageTitle = TextStyle(fontFamily = SpaceGroteskSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
    val SectionLabel = TextStyle(fontFamily = SpaceGroteskSemiBold, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp)
    val Body = TextStyle(fontFamily = SpaceGroteskRegular, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val Caption = TextStyle(fontFamily = SpaceGroteskRegular, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val Button = TextStyle(fontFamily = SpaceGroteskMedium, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val ButtonBold = TextStyle(fontFamily = SpaceGroteskBold, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val TabLabel = TextStyle(fontFamily = SpaceGroteskBold, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp)
    val Input = TextStyle(fontFamily = SpaceGroteskRegular, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
}
