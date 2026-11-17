package build.raft.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Alpha-only standalone verification activity for `#proj-frontend:#55`:
 * Delegates Compose rendering & InputConnection preedit verification to `SproutCloudPhoneVerificationScreen` in `:shared`.
 */
class SproutCloudPhoneActivity : ComponentActivity() {
    companion object {
        const val ACTION_IME_DRIVER = "build.raft.app.alpha.SPROUT_IME_DRIVER"
    }

    private var imeActionTick by mutableIntStateOf(0)
    private var pendingImeOp by mutableStateOf<Pair<String, String>?>(null)

    private val imeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val op = intent?.getStringExtra("op") ?: return
            val text = intent.getStringExtra("text") ?: ""
            pendingImeOp = op to text
            imeActionTick += 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ACTION_IME_DRIVER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(imeReceiver, filter)
        }

        setContent {
            SproutCloudPhoneVerificationScreen(
                imeTick = imeActionTick,
                imeOp = pendingImeOp,
            )
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(imeReceiver) }
        super.onDestroy()
    }
}
