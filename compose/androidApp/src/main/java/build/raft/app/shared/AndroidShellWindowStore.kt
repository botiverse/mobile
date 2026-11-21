package build.raft.app.shared

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

private const val ANDROID_SHELL_WINDOW_STORE_KEY = "android_shell_window_registry_v1"
private const val PENDING_LAUNCHER_LAUNCH_TTL_MS = 10_000L

internal data class PersistedAndroidShellWindow(
    val shellInstanceId: String,
    val taskId: Int,
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val documentShell: Boolean,
    val lastSeenAtMillis: Long,
)

internal data class AndroidAppTaskSnapshot(
    val taskId: Int,
    val isAlive: Boolean,
    val baseIntentData: String?,
    val baseActivityClassName: String?,
)

internal data class ReconciledAndroidShellWindow(
    val shellInstanceId: String,
    val taskId: Int,
    val serverId: String,
    val documentShell: Boolean,
    val routeOwner: AndroidShellRouteOwner,
    val isAlive: Boolean,
)

/**
 * Parses a canonical document shell URI (`slock://shell/<enc env>/<enc acct>/<enc server>`) back
 * into its [AndroidShellRouteOwner], or null for any other URI.
 */
internal fun parseAndroidShellRouteOwnerFromUri(uriText: String?): AndroidShellRouteOwner? {
    val raw = uriText?.trim()?.takeIf { it.startsWith("slock://shell/") } ?: return null
    val segments = raw.removePrefix("slock://shell/").split('/')
    if (segments.size != 3 || segments.any { it.isEmpty() }) return null
    return runCatching {
        AndroidShellRouteOwner(
            environmentId = URLDecoder.decode(segments[0], "UTF-8"),
            accountId = URLDecoder.decode(segments[1], "UTF-8"),
            serverId = URLDecoder.decode(segments[2], "UTF-8"),
        ).normalizedOrNull()
    }.getOrNull()
}

internal fun snapshotAndroidAppTasks(context: Context): List<AndroidAppTaskSnapshot>? =
    runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        manager.appTasks.mapNotNull { appTask ->
            val info = runCatching { appTask.taskInfo }.getOrNull() ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val stableTaskId = info.taskId.takeIf { it >= 0 } ?: info.persistentId.takeIf { it >= 0 } ?: return@mapNotNull null
            AndroidAppTaskSnapshot(
                taskId = stableTaskId,
                isAlive = info.numActivities > 0,
                baseIntentData = info.baseIntent?.dataString,
                baseActivityClassName = info.baseIntent?.component?.className,
            )
        }
    }.getOrNull()

/**
 * Persisted Android window-to-server index backed by the app's MMKV preference store (zero disk
 * SharedPreferences on the main thread, zero `runBlocking`). Survives process death so switching
 * back to a workspace whose window died in Recents revives that existing task instead of spawning
 * a duplicate document window.
 */
internal object AndroidShellWindowStore {
    private val lock = Any()

    @Volatile
    internal var storeProvider: () -> SharedPreferenceStore? = { runCatching { raftSharedPreferenceStore() }.getOrNull() }

    @Volatile
    internal var nowMillisProvider: () -> Long = { System.currentTimeMillis() }

    fun recordRegistered(
        target: AndroidShellLaunchTarget,
        taskId: Int,
        fallbackOwner: AndroidShellRouteOwner?,
    ) {
        if (taskId < 0) return
        val owner = target.routeOwner ?: fallbackOwner ?: return
        synchronized(lock) {
            val entries = readLocked().associateBy { it.shellInstanceId }.toMutableMap()
            val existing = entries[target.shellInstanceId]
            val preservedLauncherServer =
                if (!target.documentShell && target.serverId.isNullOrBlank()) {
                    existing
                        ?.takeIf { it.environmentId == owner.environmentId && it.accountId == owner.accountId }
                        ?.serverId
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            val serverId =
                target.serverId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: preservedLauncherServer
                    ?: owner.serverId
            if (serverId.isBlank()) return
            entries[target.shellInstanceId] =
                PersistedAndroidShellWindow(
                    shellInstanceId = target.shellInstanceId,
                    taskId = taskId,
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = serverId,
                    documentShell = target.documentShell,
                    lastSeenAtMillis = nowMillisProvider(),
                )
            writeLocked(entries.values.toList())
        }
    }

    fun recordServerClaim(
        shellInstanceId: String,
        serverId: String?,
        currentOwner: AndroidShellRouteOwner?,
    ) {
        val normalizedShell = shellInstanceId.trim()
        if (normalizedShell.isEmpty()) return
        val normalizedServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            val entries = readLocked().associateBy { it.shellInstanceId }.toMutableMap()
            if (normalizedServer == null) {
                if (entries.remove(normalizedShell) != null) writeLocked(entries.values.toList())
                return
            }
            val live = AndroidShellWindowRegistry.windowOrNull(normalizedShell)
            val existing = entries[normalizedShell]
            val taskId = live?.second?.takeIf { it >= 0 } ?: existing?.taskId?.takeIf { it >= 0 } ?: return
            val documentShell = live?.first?.documentShell ?: existing?.documentShell ?: (normalizedShell != DEFAULT_ANDROID_SHELL_INSTANCE_ID)
            val owner =
                live?.first?.routeOwner
                    ?: currentOwner
                    ?: existing?.let { AndroidShellRouteOwner(it.environmentId, it.accountId, normalizedServer).normalizedOrNull() }
                    ?: return
            val updated =
                PersistedAndroidShellWindow(
                    shellInstanceId = normalizedShell,
                    taskId = taskId,
                    environmentId = owner.environmentId,
                    accountId = owner.accountId,
                    serverId = normalizedServer,
                    documentShell = documentShell,
                    lastSeenAtMillis = nowMillisProvider(),
                )
            if (existing?.taskId == updated.taskId &&
                existing.environmentId == updated.environmentId &&
                existing.accountId == updated.accountId &&
                existing.serverId == updated.serverId &&
                existing.documentShell == updated.documentShell
            ) {
                return
            }
            entries[normalizedShell] = updated
            writeLocked(entries.values.toList())
        }
    }

    fun remove(shellInstanceId: String) {
        val normalizedShell = shellInstanceId.trim()
        if (normalizedShell.isEmpty()) return
        synchronized(lock) {
            val entries = readLocked()
            val remaining = entries.filterNot { it.shellInstanceId == normalizedShell }
            if (remaining.size != entries.size) writeLocked(remaining)
        }
    }

    fun removeByTaskId(taskId: Int) {
        if (taskId < 0) return
        synchronized(lock) {
            val entries = readLocked()
            val remaining = entries.filterNot { it.taskId == taskId }
            if (remaining.size != entries.size) writeLocked(remaining)
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { storeProvider()?.putString(ANDROID_SHELL_WINDOW_STORE_KEY, null) }
        }
    }

    /**
     * Returns the persisted server bound to the launcher window (`android-default-app-shell`) for
     * the current environment and account, so reviving a dead launcher task restores its own
     * workspace even if another window updated the global selected server in MMKV.
     */
    fun persistedLauncherServerId(
        environmentId: String,
        accountId: String,
    ): String? {
        val env = environmentId.trim().trimEnd('/')
        val acct = accountId.trim()
        if (env.isEmpty() || acct.isEmpty()) return null
        return synchronized(lock) {
            readLocked()
                .firstOrNull {
                    it.shellInstanceId == DEFAULT_ANDROID_SHELL_INSTANCE_ID &&
                        !it.documentShell &&
                        it.environmentId == env &&
                        it.accountId == acct &&
                        it.serverId.isNotBlank()
                }
                ?.serverId
        }
    }

    /**
     * Reconciles persisted windows against Android's live/dead [appTasks] list for the signed-in
     * `(environmentId, accountId)`, pruning entries whose tasks disappeared from Recents or belong
     * to another account/environment, and discovering dead document tasks from their canonical URI.
     * When [appTasks] is null (e.g. `ActivityManager` unavailable or threw), returns empty without
     * pruning the persisted store.
     */
    fun reconcile(
        appTasks: List<AndroidAppTaskSnapshot>?,
        environmentId: String,
        accountId: String,
        prune: Boolean = true,
    ): List<ReconciledAndroidShellWindow> {
        if (appTasks == null) return emptyList()
        val env = environmentId.trim().trimEnd('/')
        val acct = accountId.trim()
        if (env.isEmpty() || acct.isEmpty()) return emptyList()
        val tasksById = appTasks.associateBy { it.taskId }
        val result = linkedMapOf<String, ReconciledAndroidShellWindow>()
        synchronized(lock) {
            val stored = readLocked()
            val kept = mutableListOf<PersistedAndroidShellWindow>()
            for (entry in stored) {
                val task = tasksById[entry.taskId] ?: continue
                if (entry.environmentId != env || entry.accountId != acct) continue
                val taskUriOwner = parseAndroidShellRouteOwnerFromUri(task.baseIntentData)
                if (!entry.documentShell && taskUriOwner != null) continue
                if (entry.documentShell && taskUriOwner?.shellCanonicalUri() != entry.shellInstanceId) continue
                val owner = AndroidShellRouteOwner(env, acct, entry.serverId).normalizedOrNull() ?: continue
                kept += entry
                result[entry.shellInstanceId] =
                    ReconciledAndroidShellWindow(
                        shellInstanceId = entry.shellInstanceId,
                        taskId = entry.taskId,
                        serverId = entry.serverId,
                        documentShell = entry.documentShell,
                        routeOwner = owner,
                        isAlive = task.isAlive,
                    )
            }
            if (prune && kept.size != stored.size) {
                writeLocked(kept)
            }
        }
        // Dead document tasks carry their canonical owner in baseIntent.data even without persistence.
        for (task in appTasks) {
            val owner = parseAndroidShellRouteOwnerFromUri(task.baseIntentData) ?: continue
            if (owner.environmentId != env || owner.accountId != acct) continue
            val canonical = owner.shellCanonicalUri()
            if (canonical !in result) {
                result[canonical] =
                    ReconciledAndroidShellWindow(
                        shellInstanceId = canonical,
                        taskId = task.taskId,
                        serverId = owner.serverId,
                        documentShell = true,
                        routeOwner = owner,
                        isAlive = task.isAlive,
                    )
            }
        }
        return result.values.toList()
    }

    private fun readLocked(): List<PersistedAndroidShellWindow> {
        val raw = runCatching { storeProvider()?.getString(ANDROID_SHELL_WINDOW_STORE_KEY) }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val shellId = item.optString("shellInstanceId").trim()
                    val taskId = item.optInt("taskId", -1)
                    val env = item.optString("environmentId").trim().trimEnd('/')
                    val acct = item.optString("accountId").trim()
                    val server = item.optString("serverId").trim()
                    if (shellId.isEmpty() || taskId < 0 || env.isEmpty() || acct.isEmpty() || server.isEmpty()) continue
                    add(
                        PersistedAndroidShellWindow(
                            shellInstanceId = shellId,
                            taskId = taskId,
                            environmentId = env,
                            accountId = acct,
                            serverId = server,
                            documentShell = item.optBoolean("documentShell", shellId != DEFAULT_ANDROID_SHELL_INSTANCE_ID),
                            lastSeenAtMillis = item.optLong("lastSeenAtMillis", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(entries: List<PersistedAndroidShellWindow>) {
        val store = storeProvider() ?: return
        if (entries.isEmpty()) {
            runCatching { store.putString(ANDROID_SHELL_WINDOW_STORE_KEY, null) }
            return
        }
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("shellInstanceId", entry.shellInstanceId)
                    .put("taskId", entry.taskId)
                    .put("environmentId", entry.environmentId)
                    .put("accountId", entry.accountId)
                    .put("serverId", entry.serverId)
                    .put("documentShell", entry.documentShell)
                    .put("lastSeenAtMillis", entry.lastSeenAtMillis)
            )
        }
        runCatching { store.putString(ANDROID_SHELL_WINDOW_STORE_KEY, array.toString()) }
    }
}

/**
 * One-shot handoff for reviving a dead launcher task via `AppTask.moveToFront()` (which carries no
 * Intent extras): [MainActivity] (after force-stop) or [RaftAppShellActivity] (after LMK) consumes
 * it once to land on the intended server and optional kept tab.
 */
internal data class PendingLauncherTaskLaunch(
    val taskId: Int,
    val serverId: String?,
    val initialTab: String?,
    val tapEpochMillis: Long,
    val stashedAtElapsedMs: Long,
) {
    val hasExplicitDestination: Boolean
        get() = !initialTab.isNullOrBlank()
}

internal object AndroidPendingLauncherTaskLaunchStore {
    private val lock = Any()
    private var pending: PendingLauncherTaskLaunch? = null

    @Volatile
    internal var elapsedMsProvider: () -> Long = { SystemClock.elapsedRealtime() }

    fun stash(
        taskId: Int,
        serverId: String?,
        initialTab: String?,
        tapEpochMillis: Long = 0L,
    ) {
        if (taskId < 0) return
        synchronized(lock) {
            pending =
                PendingLauncherTaskLaunch(
                    taskId = taskId,
                    serverId = serverId?.trim()?.takeIf { it.isNotEmpty() },
                    initialTab = initialTab?.trim()?.takeIf { it.isNotEmpty() },
                    tapEpochMillis = tapEpochMillis.coerceAtLeast(0L),
                    stashedAtElapsedMs = elapsedMsProvider(),
                )
        }
    }

    fun consume(taskId: Int): PendingLauncherTaskLaunch? {
        if (taskId < 0) return null
        val now = elapsedMsProvider()
        return synchronized(lock) {
            val current = pending ?: return@synchronized null
            if (now - current.stashedAtElapsedMs > PENDING_LAUNCHER_LAUNCH_TTL_MS) {
                pending = null
                return@synchronized null
            }
            if (current.taskId != taskId) return@synchronized null
            pending = null
            current
        }
    }

    fun clear() {
        synchronized(lock) { pending = null }
    }
}
