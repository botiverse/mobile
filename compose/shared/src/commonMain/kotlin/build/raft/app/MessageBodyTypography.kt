package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.sp

/**
 * Message body steps, matching web (store/appearanceStore.ts after slock #10148):
 * `md` is raft-ui's `text-body` (16/24); `sm` / `lg` are the Tailwind `text-sm` (14/20)
 * and `text-lg` (18/28) companions.
 */
internal fun slockMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        "sm" -> SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack)
        "lg" -> SlockType.Body.copy(fontSize = 18.sp, lineHeight = 28.sp, color = SlockColor.TextBlack)
        else -> SlockType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp, color = SlockColor.TextBlack)
    }
