package build.raft.designsystem.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private object RaftAndroidReducedMotionObserver {
    private val lock = Any()
    private val _reduced = MutableStateFlow(false)

    @Volatile
    private var registeredAppContext: WeakReference<Context>? = null
    private var registeredObserver: ContentObserver? = null

    fun ensureObserved(context: Context): StateFlow<Boolean> {
        val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
        if (registeredAppContext?.get() === appContext) return _reduced
        synchronized(lock) {
            if (registeredAppContext?.get() === appContext) return _reduced
            val previousContext = registeredAppContext?.get()
            val previousObserver = registeredObserver
            if (previousContext != null && previousObserver != null) {
                runCatching { previousContext.contentResolver?.unregisterContentObserver(previousObserver) }
            }
            registeredAppContext = null
            registeredObserver = null

            val appRef = WeakReference(appContext)
            val registered =
                runCatching {
                    val resolver = appContext.contentResolver ?: return@runCatching false
                    val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE) ?: return@runCatching false
                    val looper = Looper.getMainLooper() ?: return@runCatching false
                    val observer =
                        object : ContentObserver(Handler(looper)) {
                            override fun onChange(selfChange: Boolean) {
                                val target = appRef.get() ?: return
                                _reduced.value = readReducedMotion(target)
                            }
                        }
                    resolver.registerContentObserver(uri, false, observer)
                    registeredAppContext = appRef
                    registeredObserver = observer
                    true
                }.getOrDefault(false)
            // Read after registration (closes subscribe race) or fall back to direct read if registration failed.
            val currentValue = readReducedMotion(appContext)
            if (_reduced.value != currentValue || registered) {
                _reduced.value = currentValue
            }
        }
        return _reduced
    }

    private fun readReducedMotion(context: Context): Boolean =
        runCatching {
            val resolver = context.contentResolver ?: return false
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
}

@Composable
actual fun rememberRaftReducedMotion(): Boolean {
    val context = LocalContext.current
    // Accessibility > Remove animations sets the animator duration scale to 0.
    // Backed by a single process-wide ContentObserver on applicationContext so per-row callers
    // (e.g. LazyColumn rows) incur no Binder IPC churn and never retain an Activity context.
    val flow = remember(context) { RaftAndroidReducedMotionObserver.ensureObserved(context) }
    return flow.collectAsState().value
}
