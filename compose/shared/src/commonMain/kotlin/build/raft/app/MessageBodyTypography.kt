package build.raft.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// Message text is user content: Han characters in Simplified Chinese forms, like the Web.
internal fun raftMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    raftMessageBodyStep(preferenceKey).withRaftUserContentLocale()

private fun raftMessageBodyStep(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        // Web appearanceStore steps: sm text-sm (14/20, raft-design `text-callout`), md text-base
        // (16/24, `text-body`, the shared content size), lg text-lg (18/28; no raft-design token).
        "sm" -> RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack)
        "lg" -> RaftType.Body.copy(fontSize = 18.sp, lineHeight = 28.sp, color = RaftColor.TextBlack)
        else -> RaftType.Body.copy(color = RaftColor.TextBlack)
    }
