package build.raft.app

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun rememberRaftHardwareKeyboardConnected(): Boolean {
    val configuration = LocalConfiguration.current
    return raftAndroidHardwareKeyboardConnected(configuration.keyboard, configuration.hardKeyboardHidden)
}

/**
 * A hardware keyboard is attached and usable: the configuration reports keys
 * ([Configuration.keyboard] neither [Configuration.KEYBOARD_NOKEYS] nor undefined) that are not
 * hidden ([Configuration.hardKeyboardHidden] not [Configuration.HARDKEYBOARDHIDDEN_YES], which a
 * folded-away keyboard reports). Attaching or removing one changes the configuration.
 */
internal fun raftAndroidHardwareKeyboardConnected(keyboard: Int, hardKeyboardHidden: Int): Boolean =
    keyboard != Configuration.KEYBOARD_NOKEYS &&
        keyboard != Configuration.KEYBOARD_UNDEFINED &&
        hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
