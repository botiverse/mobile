package build.raft.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

internal fun slockMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        // Medium is the shared content size (SlockTextSize); small/large are one step either side.
        "sm" -> SlockType.Body.copy(fontSize = 12.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
        "lg" -> SlockType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        else -> SlockType.Body.copy(color = SlockColor.TextBlack)
    }
