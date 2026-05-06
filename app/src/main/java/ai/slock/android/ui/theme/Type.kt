package ai.slock.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ai.slock.android.R

private val SlockDisplayFontFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_bold, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

object SlockType {
    val PageTitle = TextStyle(
        fontFamily = SlockDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    val SectionLabel = TextStyle(
        fontFamily = SlockDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.2.sp
    )

    val Body = TextStyle(
        fontFamily = SlockDisplayFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    )

    val Caption = TextStyle(
        fontFamily = SlockDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    val Button = TextStyle(
        fontFamily = SlockDisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )
}

val Typography = Typography(
    headlineLarge = SlockType.PageTitle,
    titleLarge = SlockType.PageTitle,
    titleMedium = SlockType.SectionLabel,
    bodyLarge = SlockType.Body,
    bodyMedium = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = SlockType.Button,
    labelMedium = SlockType.Caption,
    labelSmall = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
)
