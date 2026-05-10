package ai.slock.android.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalSystemBarColorController =
        staticCompositionLocalOf<((Color) -> Unit)?> { null }

@Composable
fun DeclareStatusBarColor(color: Color) {
    val controller = LocalSystemBarColorController.current
    SideEffect {
        controller?.invoke(color)
    }
}
