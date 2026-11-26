package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The gentle new-version notice for store installs (Google Play, App Store).
 *
 * Hands builds learn of a newer build from the host's own update check (Settings › About, the Home
 * update banner, the More dot). Store builds have no in-app update in the host, so when the host
 * answers "unsupported" the shared settings capabilities ask the install channel
 * ([RaftInstallChannelUpdates], the same lookup the minimum build gate uses) through
 * [RaftStoreUpdateChecker] and turn its answer into the same [SharedSettingsUpdateCheckSnapshot]
 * ([raftStoreUpdateCheckSnapshot]). Everything downstream is unchanged: the Home banner and More
 * dot, the About row, the update page and its "Skip this version" (which hides the notice until a
 * newer build appears). Update opens the store page. Never a dialog, never a block.
 */

/** A store answer is reused this long before the store is asked again (app start, then every few hours). */
@RaftCoreInternalApi const val RAFT_STORE_UPDATE_CHECK_INTERVAL_MILLIS: Long = 4L * 60L * 60_000L

/** An unknown answer (offline, store error) is asked again sooner, on the Home notice refresh cadence. */
@RaftCoreInternalApi const val RAFT_STORE_UPDATE_RETRY_MILLIS: Long = 30L * 60_000L

@RaftCoreInternalApi const val RAFT_STORE_UPDATE_TIMEOUT_MILLIS: Long = 20_000L

/** What the store channel [channel] said, with its fallback page. */
data class RaftStoreUpdateAnswer(
    val channel: String,
    val latest: RaftInstallChannelLatest,
    val fallbackUrl: String? = null,
)

/**
 * Asks the store install channel at most once per [intervalMillis] ([retryMillis] after an unknown
 * answer) unless [answer] is forced (the person tapped "Check for update"). Concurrent callers share
 * one request. Null when the install has no store channel, or the channel is not a store install
 * ([RaftInstallChannelUpdates.offersStoreUpdateNotice]: Hands builds, sideloaded Play builds,
 * TestFlight and Xcode builds).
 */
class RaftStoreUpdateChecker(
    private val channel: () -> RaftInstallChannelUpdates?,
    private val now: () -> Long,
    private val logger: (String) -> Unit = {},
    private val timeoutMillis: Long = RAFT_STORE_UPDATE_TIMEOUT_MILLIS,
    private val intervalMillis: Long = RAFT_STORE_UPDATE_CHECK_INTERVAL_MILLIS,
    private val retryMillis: Long = RAFT_STORE_UPDATE_RETRY_MILLIS,
) {
    private class Held(val answer: RaftStoreUpdateAnswer, val atMillis: Long)

    private val mutex = Mutex()
    private var held: Held? = null

    suspend fun answer(force: Boolean = false): RaftStoreUpdateAnswer? {
        val source = channel() ?: return null
        val storeInstall =
            try {
                source.offersStoreUpdateNotice
            } catch (_: Throwable) {
                false
            }
        if (!storeInstall) return null
        return mutex.withLock {
            val previous = held?.takeIf { it.answer.channel == source.name }
            if (!force && previous != null) {
                val freshFor = if (previous.answer.latest == RaftInstallChannelLatest.Unknown) retryMillis else intervalMillis
                if (now() - previous.atMillis < freshFor) return@withLock previous.answer
            }
            val latest = ask(source, userInitiated = force)
            val fallback =
                try {
                    source.fallbackUpdateUrl()
                } catch (_: Throwable) {
                    null
                }
            val answer = RaftStoreUpdateAnswer(channel = source.name, latest = latest, fallbackUrl = fallback)
            held = Held(answer, now())
            logger("store_update_check channel=${source.name} latest=${latest.storeLogLabel()} force=$force")
            answer
        }
    }

    private suspend fun ask(source: RaftInstallChannelUpdates, userInitiated: Boolean): RaftInstallChannelLatest =
        try {
            withTimeoutOrNull(timeoutMillis) { source.latest(userInitiated = userInitiated) } ?: RaftInstallChannelLatest.Unknown
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("store_update_check channel_failed channel=${source.name} error=${error::class.simpleName ?: "unknown"}")
            RaftInstallChannelLatest.Unknown
        }
}

/** The process's store update checker, on the host's install channel. */
object RaftStoreUpdates {
    val checker: RaftStoreUpdateChecker by lazy {
        RaftStoreUpdateChecker(
            channel = { RaftInstallChannelUpdatesHost.current },
            now = ::defaultNowMillis,
            logger = { line -> runCatching { build.raft.app.raftLogInfo("SlockStoreUpdate", line) } },
        )
    }
}

/** The words of a store answer (the About row and the update page). */
@RaftCoreInternalApi data class RaftStoreUpdateCopy(
    val update: String = "Update",
    val detailAvailable: String = "Raft has an update.",
    val detailCurrent: String = "Raft is up to date.",
    val detailUnknown: String = "Update information is temporarily unavailable.",
)

@RaftCoreInternalApi fun raftStoreUpdateLocalizedCopy(): RaftStoreUpdateCopy =
    try {
        RaftStoreUpdateCopy(
            update = Res.string.app_update_update.desc().resolve(),
            detailAvailable = Res.string.app_update_detail_available.desc().resolve(),
            detailCurrent = Res.string.app_update_detail_current.desc().resolve(),
            detailUnknown = Res.string.app_update_unavailable.desc().resolve(),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        RaftStoreUpdateCopy()
    }

@RaftCoreInternalApi const val RAFT_STORE_UPDATE_UNKNOWN_ERROR_CODE = "store_update_unknown"

/**
 * A store answer as the update snapshot the Hands notice already reads (pure).
 *
 * - The store offers a build newer than [currentBuild] with a page to open: an available update
 *   (`Idle`, so the update page offers Update and "Skip this version"); Update opens that page.
 * - [minBuildGateBlocking]: the minimum build gate already blocks the app with its own Update
 *   page, so no second notice.
 * - Up to date (or not newer): no update. Unknown, or no page to open: no update, and the About
 *   row says the information is unavailable.
 */
@RaftCoreInternalApi fun raftStoreUpdateCheckSnapshot(
    answer: RaftStoreUpdateAnswer,
    currentBuild: Long?,
    currentVersionLabel: String,
    minBuildGateBlocking: Boolean = false,
    copy: RaftStoreUpdateCopy = RaftStoreUpdateCopy(),
): SharedSettingsUpdateCheckSnapshot {
    val current = currentBuild?.takeIf { it >= RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR }
    val base =
        SharedSettingsUpdateCheckSnapshot(
            supported = true,
            storeChannel = answer.channel,
            currentVersionLabel = currentVersionLabel.trim(),
            currentVersionCode = current ?: 0L,
        )
    if (minBuildGateBlocking) return base
    val latest = answer.latest
    val fallback = answer.fallbackUrl?.trim()?.takeIf { it.startsWith("https://") }
    return when (latest) {
        is RaftInstallChannelLatest.Available -> {
            val url = latest.updateUrl?.trim()?.takeIf { it.startsWith("https://") } ?: fallback
            when {
                current == null || url == null ->
                    base.copy(detail = copy.detailUnknown, errorCode = RAFT_STORE_UPDATE_UNKNOWN_ERROR_CODE)
                latest.build <= current -> base.copy(detail = copy.detailCurrent)
                else ->
                    base.copy(
                        transactionState = SharedSettingsUpdateTransactionState.Idle,
                        statusLabel = copy.update,
                        actionLabel = copy.update,
                        detail = copy.detailAvailable,
                        updateAvailable = true,
                        latestVersionCode = latest.build,
                        latestVersionLabel = raftVersionNameOfBuildNumber(latest.build).orEmpty(),
                        downloadUrl = url,
                        updatePriority = latest.updatePriority,
                        stalenessDays = latest.stalenessDays,
                    )
            }
        }
        RaftInstallChannelLatest.UpToDate -> base.copy(detail = copy.detailCurrent)
        RaftInstallChannelLatest.Unknown ->
            base.copy(detail = copy.detailUnknown, errorCode = RAFT_STORE_UPDATE_UNKNOWN_ERROR_CODE)
    }
}

/** `major.minor.patch` of a build number (12601003 -> `1.26.1`), or null when it is not one. */
@RaftCoreInternalApi fun raftVersionNameOfBuildNumber(build: Long): String? {
    if (build < RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR) return null
    val major = build / 10_000_000L
    val minor = (build / 100_000L) % 100L
    val patch = (build / 1_000L) % 100L
    if (major > 209L) return null
    return "$major.$minor.$patch"
}

private fun RaftInstallChannelLatest.storeLogLabel(): String =
    when (this) {
        RaftInstallChannelLatest.Unknown -> "unknown"
        RaftInstallChannelLatest.UpToDate -> "up_to_date"
        is RaftInstallChannelLatest.Available -> "available:$build"
    }
