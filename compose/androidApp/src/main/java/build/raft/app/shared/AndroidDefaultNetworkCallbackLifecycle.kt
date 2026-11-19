package build.raft.app.shared

/**
 * When the process keeps its default-network callback registered (Hands a8cd6426, fe049641).
 *
 * Every callback ConnectivityService sends is a binder transaction into this process. A cached
 * process is frozen, and on Android 17 a process whose freeze keeps failing because binder
 * transactions are pending is killed ("excessive binder traffic during cached"). Nothing hidden
 * needs the callback unless a background connection is being kept up, so it is registered only
 * while the app is in the foreground, background keep-alive is on, or the background connection
 * service is running (or starting).
 */
internal object AndroidDefaultNetworkCallbackPolicy {
    fun shouldBeRegistered(
        foreground: Boolean,
        backgroundKeepAliveEnabled: Boolean,
        backgroundConnectionServiceActive: Boolean,
    ): Boolean = foreground || backgroundKeepAliveEnabled || backgroundConnectionServiceActive
}

internal enum class AndroidNetworkCallbackTransition {
    Registered,
    Unregistered,
    Unchanged,
    RegisterFailed,
    UnregisterFailed,
}

/**
 * Registers and unregisters the default-network callback as [sync] is told. [register] creates and
 * registers a fresh callback (it throws on failure); [unregister] removes the one registered last.
 * Idempotent, and safe from any thread.
 */
internal class AndroidDefaultNetworkCallbackRegistration(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
) {
    private val lock = Any()

    @Volatile
    var registered: Boolean = false
        private set

    fun sync(wanted: Boolean): AndroidNetworkCallbackTransition =
        synchronized(lock) {
            when {
                wanted == registered -> AndroidNetworkCallbackTransition.Unchanged
                wanted ->
                    runCatching(register).fold(
                        onSuccess = {
                            registered = true
                            AndroidNetworkCallbackTransition.Registered
                        },
                        onFailure = { AndroidNetworkCallbackTransition.RegisterFailed },
                    )
                else ->
                    // Counted as gone either way: a callback the system no longer knows is not
                    // registered, and a later register must not be skipped because of it.
                    runCatching(unregister).fold(
                        onSuccess = {
                            registered = false
                            AndroidNetworkCallbackTransition.Unregistered
                        },
                        onFailure = {
                            registered = false
                            AndroidNetworkCallbackTransition.UnregisterFailed
                        },
                    )
            }
        }
}

internal enum class AndroidNetworkCallbackKind(val token: String) {
    Available("available"),
    Capabilities("capabilities"),
    LinkProperties("link_properties"),
    Lost("lost"),
}

/**
 * Counts default-network callbacks that reach the process while it is hidden, one summary line per
 * minute at most: `app_scope_network_callback_hidden_summary windowMs= total= available=
 * capabilities= link_properties= lost=`. A window starts at its first hidden callback; it is
 * reported by the first callback a minute or more later, or by [flush] (back in front, unregister).
 * Foreground callbacks are not counted. Thread-safe.
 */
internal class AndroidHiddenNetworkCallbackCounter(
    private val windowMillis: Long = 60_000L,
) {
    private val lock = Any()
    private var windowStartMillis: Long? = null
    private val counts = IntArray(AndroidNetworkCallbackKind.entries.size)

    /** Records one callback; returns a summary line to log when it closed the previous window. */
    fun record(kind: AndroidNetworkCallbackKind, hidden: Boolean, nowMillis: Long): String? {
        if (!hidden) return null
        synchronized(lock) {
            val start = windowStartMillis
            val summary =
                if (start != null && nowMillis - start >= windowMillis) summaryLocked(nowMillis).also { resetLocked() } else null
            if (windowStartMillis == null) windowStartMillis = nowMillis
            counts[kind.ordinal] += 1
            return summary
        }
    }

    /** The open window's summary (null when nothing was counted); starts over. */
    fun flush(nowMillis: Long): String? =
        synchronized(lock) {
            if (windowStartMillis == null) return null
            summaryLocked(nowMillis).also { resetLocked() }
        }

    private fun summaryLocked(nowMillis: Long): String {
        val start = windowStartMillis ?: nowMillis
        return buildString {
            append("app_scope_network_callback_hidden_summary windowMs=")
            append((nowMillis - start).coerceAtLeast(0L))
            append(" total=")
            append(counts.sum())
            AndroidNetworkCallbackKind.entries.forEach { kind ->
                append(' ').append(kind.token).append('=').append(counts[kind.ordinal])
            }
        }
    }

    private fun resetLocked() {
        windowStartMillis = null
        counts.fill(0)
    }
}
