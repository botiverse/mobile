package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.sp

internal fun slockMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        // Medium is the shared content size (SlockTextSize); small/large are one step either side.
        "sm" -> SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)
        "lg" -> SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        else -> SlockType.Body.copy(color = SlockColor.TextBlack)
    }
