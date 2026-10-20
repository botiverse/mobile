package build.raft.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

internal fun raftMessageBodyTextStyle(preferenceKey: String?): TextStyle =
    when (preferenceKey?.trim()?.lowercase()) {
        // Medium is the shared content size (RaftTextSize); small/large are one step either side.
        "sm" -> RaftType.Body.copy(fontSize = 12.sp, lineHeight = 18.sp, color = RaftColor.TextBlack)
        "lg" -> RaftType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp, color = RaftColor.TextBlack)
        else -> RaftType.Body.copy(color = RaftColor.TextBlack)
    }
