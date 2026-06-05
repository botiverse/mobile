package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.pager.Pager

@Composable
internal fun SlockBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    if (!enabled) return

    val pager = LocalActivity.current.getPager() as Pager
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(pager) {
        val callback =
            object : BackPressCallback() {
                override fun handleOnBackPressed() {
                    currentOnBack()
                }
            }
        pager.getBackPressHandler().addCallback(callback)
        onDispose { pager.getBackPressHandler().removeCallback(callback) }
    }
}
