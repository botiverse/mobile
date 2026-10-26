package build.raft.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The bridge `toast`: one transient message at the bottom of the window. */
object RaftDesktopToasts {
    private var current by mutableStateOf<Pair<Long, String>?>(null)
    private var sequence = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        current = (++sequence) to text
    }

    @Composable
    fun Overlay() {
        val toast = current ?: return
        LaunchedEffect(toast.first) {
            delay(2_500)
            if (current?.first == toast.first) current = null
        }
        Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
            BasicText(
                toast.second,
                style = TextStyle(color = Color.White, fontSize = 14.sp),
                modifier =
                    Modifier.background(Color(0xE6202020), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
