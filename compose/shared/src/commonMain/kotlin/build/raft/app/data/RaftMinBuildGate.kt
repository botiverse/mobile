package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.RaftAppIdentityRegistry
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimum supported build, Android (#wg-mobile-sync #98, design 87734d32, approved 8a593cf8).
 *
 * Variant format (both keys): the flag's **default variant** is the minimum supported build NUMBER
 * as a plain decimal integer, never a version name. Build number = major * 10_000_000 +
 * minor * 100_000 + patch * 1_000 + build (scripts/ci/version_code.sh), so 1.20.0 is `12000000`
 * and 1.26.1 build 3 is `12601003`. The flag ON with such a variant turns the gate on; the flag
 * off, no variant, anything that is not a positive integer (`1.20.0`, `12,000,000`, `-1`), or a
 * number below [RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR] (1.0.0) means no gate
 * ([raftMinBuildGateThreshold]). Created by the flag admin as high-risk + long-lived, default off.
 */
const val MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY: String = "mobile_min_build_android_v1"

/** Minimum supported build, iOS. Same variant format as [MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY]. */
const val MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY: String = "mobile_min_build_ios_v1"

/** The smallest threshold the gate accepts: 1.0.0. Anything lower is a mistake, so no gate. */
const val RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR: Long = 10_000_000L

/** The gate key for this client's OS ([featureFlagClientOs]); null (no gate) on desktop. */
internal fun raftMinBuildGateFlagKey(os: String): String? =
    when (os) {
        FEATURE_FLAG_CLIENT_OS_ANDROID -> MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY
        FEATURE_FLAG_CLIENT_OS_IOS -> MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY
        else -> null
    }

/**
 * A build number as the variant or the app identity carries it: plain ASCII digits, positive, at
 * least [RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR]. Anything else is null (read as "unknown").
 */
fun parseRaftBuildNumber(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.length > 12 || !text.all { it in '0'..'9' }) return null
    val value = text.toLongOrNull() ?: return null
    return value.takeIf { it >= RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR }
}

/** The threshold the flag sets, or null for no gate (flag off, or a variant [parseRaftBuildNumber] rejects). */
fun raftMinBuildGateThreshold(flagEnabled: Boolean, variant: String?): Long? =
    if (flagEnabled) parseRaftBuildNumber(variant) else null

/**
 * The lowest build number of a store version name (`1.26.0` -> 12600000): the store lists a version
 * name, not a build, so its build part counts as 0. Null when it is not `major.minor.patch` within
 * the build-number ranges.
 */
fun raftBuildNumberOfVersionName(versionName: String?): Long? {
    val parts = versionName?.trim()?.split('.') ?: return null
    if (parts.size !in 2..3) return null
    val numbers = parts.map { part -> part.takeIf { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }?.toIntOrNull() ?: return null }
    val major = numbers[0]
    val minor = numbers[1]
    val patch = numbers.getOrElse(2) { 0 }
    if (major !in 1..209 || minor !in 0..99 || patch !in 0..99) return null
    return major * 10_000_000L + minor * 100_000L + patch * 1_000L
}

/** What this app's install channel (Hands, Google Play, App Store) says about a newer build. */
sealed interface RaftInstallChannelLatest {
    /** Could not ask, or the answer was unreadable (offline, not installed from the store, error). */
    data object Unknown : RaftInstallChannelLatest

    /** The channel has nothing newer than this build. */
    data object UpToDate : RaftInstallChannelLatest

    /** The channel offers [build] for this install; [updateUrl] is where the person gets it. */
    data class Available(val build: Long, val updateUrl: String?) : RaftInstallChannelLatest
}

/**
 * The platform host's install channel. Android installs one (Hands for APK builds, Google Play for
 * the `play` build); iOS has the App Store lookup built in; desktop has none.
 */
interface RaftInstallChannelUpdates {
    /** A short name for logs (`hands`, `play`, `app_store`). */
    val name: String

    /** Asks the channel; may throw (counted as [RaftInstallChannelLatest.Unknown]). */
    suspend fun latest(): RaftInstallChannelLatest

    /** Where to send the person when the channel could not be asked (store page), or null. */
    fun fallbackUpdateUrl(): String? = null
}

/** Where the host installs its [RaftInstallChannelUpdates]. */
object RaftInstallChannelUpdatesHost {
    @Volatile
    var installed: RaftInstallChannelUpdates? = null

    val current: RaftInstallChannelUpdates?
        get() = installed ?: raftPlatformInstallChannelUpdates()
}

/** The platform's built-in channel when the host installed none (iOS: App Store lookup). */
internal expect fun raftPlatformInstallChannelUpdates(): RaftInstallChannelUpdates?

/** What the app shows for the minimum build gate. */
sealed interface RaftMinBuildGateDecision {
    /** No gate: flag off or unreadable, build at or above the threshold, or not decided yet. */
    data object None : RaftMinBuildGateDecision

    /**
     * Below the threshold, but the channel did not confirm a build at or above it (unknown,
     * offline, or the store's latest is lower: the threshold was set too high). A dismissible
     * notice only, never a block.
     */
    data class Banner(val threshold: Long, val updateUrl: String?) : RaftMinBuildGateDecision

    /** Below the threshold and the channel offers [latestBuild] >= threshold at [updateUrl]. */
    data class Block(val threshold: Long, val latestBuild: Long, val updateUrl: String) : RaftMinBuildGateDecision
}

/**
 * The gate's decision (pure). Blocks only when every fact is certain: the flag is on with a valid
 * threshold, this build's number is known and below it, and the install channel offers a build
 * at or above it with somewhere to get it. Below the threshold without that confirmation it is
 * only a [RaftMinBuildGateDecision.Banner]; any other uncertainty is no gate.
 */
fun decideRaftMinBuildGate(
    flagEnabled: Boolean,
    variant: String?,
    currentBuild: Long?,
    channel: RaftInstallChannelLatest,
    fallbackUpdateUrl: String? = null,
): RaftMinBuildGateDecision {
    val threshold = raftMinBuildGateThreshold(flagEnabled, variant) ?: return RaftMinBuildGateDecision.None
    val current = currentBuild?.takeIf { it >= RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR } ?: return RaftMinBuildGateDecision.None
    if (current >= threshold) return RaftMinBuildGateDecision.None
    val fallback = fallbackUpdateUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (channel is RaftInstallChannelLatest.Available) {
        val url = channel.updateUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (channel.build >= threshold && channel.build > current && url != null) {
            return RaftMinBuildGateDecision.Block(threshold = threshold, latestBuild = channel.build, updateUrl = url)
        }
        return RaftMinBuildGateDecision.Banner(threshold, url ?: fallback)
    }
    return RaftMinBuildGateDecision.Banner(threshold, fallback)
}

/**
 * Follows this OS's gate flag through the existing flag client: every accepted evaluation of it
 * ([onEvaluation], fed by [SharedServerFeatureFlags]' observer, so app start / server select, the
 * periodic refresh, `server:features-changed` pushes and reconnects) and the app returning to the
 * foreground ([onForeground], which also asks for a flag refetch). Below the threshold it asks the
 * install channel, at most once per [channelCacheMillis] for the same threshold unless the app came
 * back to the foreground, and publishes [decideRaftMinBuildGate]'s answer to [decision].
 *
 * Until the channel answers the decision stays what it was for the same threshold, else None. Flag
 * off (or turned off) publishes None at once, so an open block page goes away on the next read.
 */
class RaftMinBuildGateController(
    private val scope: CoroutineScope,
    private val flagKey: String?,
    private val currentBuild: () -> Long?,
    private val channel: () -> RaftInstallChannelUpdates?,
    private val now: () -> Long,
    private val logger: (String) -> Unit = {},
    private val channelTimeoutMillis: Long = RAFT_MIN_BUILD_GATE_CHANNEL_TIMEOUT_MILLIS,
    private val channelCacheMillis: Long = RAFT_MIN_BUILD_GATE_CHANNEL_CACHE_MILLIS,
) {
    private class Reading(val enabled: Boolean, val variant: String?)

    private class Checked(val threshold: Long, val latest: RaftInstallChannelLatest, val fallbackUrl: String?, val atMillis: Long)

    private val lock = SynchronizedObject()
    private var reading: Reading? = null
    private var lastServerId: ServerId? = null
    private var checked: Checked? = null
    private var checking: Job? = null
    private var checkingThreshold: Long? = null
    private var generation = 0L

    private val decisionState = MutableStateFlow<RaftMinBuildGateDecision>(RaftMinBuildGateDecision.None)
    val decision: StateFlow<RaftMinBuildGateDecision> = decisionState.asStateFlow()

    /** The server whose evaluation last carried the gate flag (what a foreground refetch asks). */
    val lastEvaluatedServer: ServerId?
        get() = synchronized(lock) { lastServerId }

    fun onEvaluation(serverId: ServerId, key: String, enabled: Boolean, variant: String?) {
        if (flagKey == null || key != flagKey) return
        synchronized(lock) {
            reading = Reading(enabled, variant)
            lastServerId = serverId
        }
        refresh(force = false, reason = "evaluation")
    }

    fun onForeground() {
        refresh(force = true, reason = "foreground")
    }

    fun onSignedOut() {
        synchronized(lock) {
            reading = null
            lastServerId = null
            checked = null
            cancelCheckLocked()
        }
        publish(RaftMinBuildGateDecision.None, reason = "signed_out")
    }

    private fun cancelCheckLocked() {
        generation += 1
        checking?.cancel()
        checking = null
        checkingThreshold = null
    }

    private fun refresh(force: Boolean, reason: String) {
        val current = currentBuild()
        var start: Pair<Long, Long>? = null
        var immediate: RaftMinBuildGateDecision? = null
        synchronized(lock) {
            val r = reading
            val threshold = r?.let { raftMinBuildGateThreshold(it.enabled, it.variant) }
            val validCurrent = current?.takeIf { it >= RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR }
            if (r == null || threshold == null || validCurrent == null || validCurrent >= threshold) {
                checked = null
                cancelCheckLocked()
                immediate = RaftMinBuildGateDecision.None
                return@synchronized
            }
            val cached = checked?.takeIf { it.threshold == threshold }
            if (!force && cached != null && now() - cached.atMillis < channelCacheMillis) {
                immediate = decideRaftMinBuildGate(r.enabled, r.variant, validCurrent, cached.latest, cached.fallbackUrl)
                return@synchronized
            }
            if (!force && checking?.isActive == true && checkingThreshold == threshold) return@synchronized
            cancelCheckLocked()
            checkingThreshold = threshold
            start = threshold to generation
            // Keep what is shown for the same threshold while asking again; a new threshold starts from none.
            val shown = decisionState.value
            if (shown.thresholdOrNull() != threshold) immediate = RaftMinBuildGateDecision.None
        }
        immediate?.let { publish(it, reason) }
        val (threshold, expectedGeneration) = start ?: return
        val job =
            scope.launch {
                val source = channel()
                val latest = askChannel(source)
                val fallback = runCatching { source?.fallbackUpdateUrl() }.getOrNull()
                var decided: RaftMinBuildGateDecision? = null
                synchronized(lock) {
                    if (generation != expectedGeneration) return@synchronized
                    checked = Checked(threshold, latest, fallback, now())
                    checking = null
                    checkingThreshold = null
                    val r = reading ?: return@synchronized
                    decided = decideRaftMinBuildGate(r.enabled, r.variant, currentBuild(), latest, fallback)
                }
                logger(
                    "min_build_gate channel=${source?.name ?: "none"} threshold=$threshold current=${current ?: "unknown"} " +
                        "latest=${latest.logLabel()} reason=$reason",
                )
                decided?.let { publish(it, reason = "channel") }
            }
        synchronized(lock) {
            if (generation == expectedGeneration && checkingThreshold == threshold) checking = job
        }
    }

    private suspend fun askChannel(source: RaftInstallChannelUpdates?): RaftInstallChannelLatest {
        source ?: return RaftInstallChannelLatest.Unknown
        return try {
            withTimeoutOrNull(channelTimeoutMillis) { source.latest() } ?: RaftInstallChannelLatest.Unknown
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("min_build_gate channel_failed channel=${source.name} error=${error::class.simpleName ?: "unknown"}")
            RaftInstallChannelLatest.Unknown
        }
    }

    private fun publish(decision: RaftMinBuildGateDecision, reason: String) {
        val previous = decisionState.value
        decisionState.value = decision
        if (previous != decision) logger("min_build_gate decision=${decision.logLabel()} reason=$reason")
    }
}

internal const val RAFT_MIN_BUILD_GATE_CHANNEL_TIMEOUT_MILLIS: Long = 20_000L
internal const val RAFT_MIN_BUILD_GATE_CHANNEL_CACHE_MILLIS: Long = 30L * 60_000L

private fun RaftMinBuildGateDecision.thresholdOrNull(): Long? =
    when (this) {
        RaftMinBuildGateDecision.None -> null
        is RaftMinBuildGateDecision.Banner -> threshold
        is RaftMinBuildGateDecision.Block -> threshold
    }

private fun RaftMinBuildGateDecision.logLabel(): String =
    when (this) {
        RaftMinBuildGateDecision.None -> "none"
        is RaftMinBuildGateDecision.Banner -> "banner threshold=$threshold"
        is RaftMinBuildGateDecision.Block -> "block threshold=$threshold latest=$latestBuild"
    }

private fun RaftInstallChannelLatest.logLabel(): String =
    when (this) {
        RaftInstallChannelLatest.Unknown -> "unknown"
        RaftInstallChannelLatest.UpToDate -> "up_to_date"
        is RaftInstallChannelLatest.Available -> "available:$build"
    }

/**
 * The process's gate: one [RaftMinBuildGateController] for this OS, fed by the flag client's
 * observer ([recordServerEvaluation]), the foreground edge of [RaftStaleReadRecoverySignals], and
 * sign-out. Pages read [decision] (the min build gate page layer).
 */
object RaftMinBuildGate {
    /** Asks the flag client to read the server's flags again (set by the shared store provider). */
    @Volatile
    var flagRefetch: ((ServerId) -> Unit)? = null

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val controller: RaftMinBuildGateController by lazy {
        RaftMinBuildGateController(
            scope = processScope,
            flagKey = raftMinBuildGateFlagKey(featureFlagClientOs()),
            currentBuild = { parseRaftBuildNumber(RaftAppIdentityRegistry.current()?.buildNumber) },
            channel = { RaftInstallChannelUpdatesHost.current },
            now = ::defaultNowMillis,
            logger = { line -> runCatching { build.raft.app.raftLogInfo("SlockMinBuildGate", line) } },
        ).also { gate ->
            processScope.launch {
                // A StateFlow is already distinct: the first value is the current one, not a return.
                RaftStaleReadRecoverySignals.foreground
                    .drop(1)
                    .filter { it }
                    .collect {
                        gate.onForeground()
                        gate.lastEvaluatedServer?.let { serverId -> runCatching { flagRefetch?.invoke(serverId) } }
                    }
            }
        }
    }

    val decision: StateFlow<RaftMinBuildGateDecision>
        get() = controller.decision

    fun recordServerEvaluation(serverId: ServerId, key: String, enabled: Boolean, variant: String?) {
        if (key != MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY && key != MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY) return
        controller.onEvaluation(serverId, key, enabled, variant)
    }

    fun onSignedOut() {
        controller.onSignedOut()
    }

    private val dismissedBannerThreshold = MutableStateFlow<Long?>(null)

    /** The banner threshold dismissed in this process (it comes back with a new threshold or a new launch). */
    val dismissedBanner: StateFlow<Long?> = dismissedBannerThreshold.asStateFlow()

    fun dismissBanner(threshold: Long) {
        dismissedBannerThreshold.value = threshold
    }
}

/** The App Store's listing of this app (`itunes.apple.com/lookup?bundleId=`): its version name and page. */
data class RaftAppStoreListing(val version: String, val trackViewUrl: String?)

@kotlinx.serialization.Serializable
private data class RaftAppStoreLookupResult(
    val version: String? = null,
    val trackViewUrl: String? = null,
    val bundleId: String? = null,
)

@kotlinx.serialization.Serializable
private data class RaftAppStoreLookupBody(
    val resultCount: Int = 0,
    val results: List<RaftAppStoreLookupResult> = emptyList(),
)

private val raftAppStoreLookupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** The listing for [bundleId] in an App Store lookup answer, or null (none, or unreadable). */
fun parseRaftAppStoreLookup(body: String, bundleId: String): RaftAppStoreListing? {
    val decoded =
        try {
            raftAppStoreLookupJson.decodeFromString(RaftAppStoreLookupBody.serializer(), body)
        } catch (_: Exception) {
            return null
        }
    val result = decoded.results.firstOrNull { it.bundleId == null || it.bundleId == bundleId } ?: return null
    val version = result.version?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftAppStoreListing(version = version, trackViewUrl = result.trackViewUrl?.trim()?.takeIf { it.startsWith("https://") })
}

/** What an App Store listing means for this build: newer (with its page), not newer, or unknown. */
fun raftAppStoreLatest(listing: RaftAppStoreListing?, currentBuild: Long?): RaftInstallChannelLatest {
    val build = raftBuildNumberOfVersionName(listing?.version) ?: return RaftInstallChannelLatest.Unknown
    val current = currentBuild?.takeIf { it >= RAFT_MIN_BUILD_GATE_THRESHOLD_FLOOR } ?: return RaftInstallChannelLatest.Unknown
    return if (build > current) {
        RaftInstallChannelLatest.Available(build = build, updateUrl = listing?.trackViewUrl)
    } else {
        RaftInstallChannelLatest.UpToDate
    }
}
