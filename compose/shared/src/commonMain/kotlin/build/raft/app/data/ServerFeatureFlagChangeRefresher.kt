package build.raft.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import build.raft.app.model.ServerId
import kotlin.random.Random
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The most a refetch waits after a change notice or a reconnect (slock featuresChanged.ts: "a random delay"). */
const val SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS: Long = 10_000L

/** A uniformly random wait in `0..maxMillis` (both ends included). */
internal fun serverFeaturesChangedJitterMillis(random: Random, maxMillis: Long = SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS): Long =
    if (maxMillis <= 0L) 0L else random.nextLong(0L, maxMillis + 1L)

/**
 * Follows `server:features-changed` (botiverse/slock `packages/shared/src/featuresChanged.ts`): the
 * Server's notice that a Server's flags may have changed. It carries no values, so this refetches
 * the Server's whole registered flag set ([refetch]), never only the hinted `keys`.
 *
 * - [onFeaturesChanged]: waits a random 0–10 s ([jitterMillis]) so one change does not make every
 *   client ask at once, then refetches. Notices for the same Server while one is waiting join it
 *   (coalesced); one arriving while the refetch is on its way schedules one more after it, since
 *   that answer may predate the change.
 * - [onReconnected]: missed notices are never replayed, so every (re)connect refetches the same
 *   way.
 * - A failed refetch keeps the values the client has ([SharedServerFeatureFlags] never replaces a
 *   snapshot with a failure); [refetch] may throw, which is logged and dropped.
 *
 * What the new values do to UI already on screen is the reader's business: a feature that turns
 * off is not torn down where it is shown; it takes effect on the next entry, as on the web
 * ([rememberServerFeatureLatched]).
 */
class ServerFeatureFlagChangeRefresher(
    private val scope: CoroutineScope,
    private val refetch: suspend (ServerId) -> Unit,
    private val jitterMillis: () -> Long = { serverFeaturesChangedJitterMillis(Random.Default) },
    private val logger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()

    private enum class Phase { Waiting, Fetching }

    private class Pending(var phase: Phase, var again: Boolean = false)

    private val pending = mutableMapOf<String, Pending>()

    fun onFeaturesChanged(serverId: ServerId, keys: List<String> = emptyList()) {
        schedule(serverId, reason = "features_changed keys=${keys.size}")
    }

    fun onReconnected(serverId: ServerId) {
        schedule(serverId, reason = "reconnect")
    }

    private fun schedule(serverId: ServerId, reason: String) {
        val id = serverId.value.trim().takeIf { it.isNotEmpty() } ?: return
        val start =
            synchronized(lock) {
                val current = pending[id]
                when {
                    current == null -> {
                        pending[id] = Pending(Phase.Waiting)
                        true
                    }
                    current.phase == Phase.Fetching -> {
                        current.again = true
                        false
                    }
                    else -> false
                }
            }
        if (!start) {
            logger("feature_flags change_coalesced server=$id reason=$reason")
            return
        }
        logger("feature_flags change_scheduled server=$id reason=$reason")
        launchRefetch(id)
    }

    private fun launchRefetch(id: String) {
        scope.launch {
            delay(jitterMillis().coerceIn(0L, SERVER_FEATURES_CHANGED_MAX_JITTER_MILLIS))
            synchronized(lock) { pending[id]?.phase = Phase.Fetching }
            try {
                refetch(ServerId(id))
            } catch (cancellation: CancellationException) {
                synchronized(lock) { pending.remove(id) }
                throw cancellation
            } catch (error: Throwable) {
                logger("feature_flags change_refetch_failed server=$id reason=${error::class.simpleName ?: "unknown"}")
            }
            val again =
                synchronized(lock) {
                    val current = pending[id]
                    if (current != null && current.again) {
                        current.again = false
                        current.phase = Phase.Waiting
                        true
                    } else {
                        pending.remove(id)
                        false
                    }
                }
            if (again) launchRefetch(id)
        }
    }
}

/**
 * Whether [key] is on for [serverId], latched for this composition: it turns on as soon as the
 * flag does, but once on it stays on until the UI that read it leaves composition (next entry),
 * so a flag switched off while a page shows the feature does not tear it down (web parity).
 */
@Composable
fun rememberServerFeatureLatched(flags: SharedServerFeatureFlags?, serverId: ServerId?, key: String): Boolean {
    val revision = flags?.snapshotRevision?.collectAsState()?.value
    // Not state: [revision] already recomposes this reader whenever the value can change.
    val latch = remember(flags, serverId?.value, key) { ServerFeatureLatch() }
    val now = remember(flags, serverId?.value, key, revision) { flags?.isEnabled(serverId, key) == true }
    return latch.read(now)
}

/** Once [read] has seen true, it answers true for good (see [rememberServerFeatureLatched]). */
internal class ServerFeatureLatch {
    private var on = false

    fun read(now: Boolean): Boolean {
        if (now) on = true
        return on
    }
}
