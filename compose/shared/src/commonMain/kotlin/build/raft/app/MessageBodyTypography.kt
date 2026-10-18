package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.sp

internal fun slockMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        // artin 2026-10-18 (1.14.1): message text one step larger than before (default 16sp,
        // closer to common chat apps); every step moves up together.
        "sm" -> SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)
        "lg" -> SlockType.Body.copy(fontSize = 18.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
        else -> SlockType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp, color = SlockColor.TextBlack)
    }
