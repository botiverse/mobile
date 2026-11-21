package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SHARE_LOOP_FEATURE_FLAG_KEY
import build.raft.app.data.currentFeatureFlagClientContext
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides

/*
 * The share loop's one gate ([SHARE_LOOP_FEATURE_FLAG_KEY], per server, default off). Everything
 * the first batch adds or changes reads it here and nowhere else: off, each surface is exactly as
 * before (DMs included) and nothing is sent.
 *
 * A review build turns it on without a server flag through the diagnostics "Local Feature
 * Overrides" registry (Settings > About > Diagnostics, Android debug / alpha builds): the key is a
 * server-exact consumer there, Force on lasts 15 minutes (the registry's TTL) and is re-read by an
 * open page.
 */

/**
 * The server's answer with the diagnostics override on top: Force on / Force off of an entitled
 * (debug / alpha) build, else the server's. Read from the registry's snapshot, not as a decision:
 * a decision is recorded and announced per read, and pages of servers with different answers
 * would then wake each other without end.
 */
internal fun resolveShareLoopEnabled(registry: DiagnosticFeatureFlagOverrideRegistry, serverEffective: Boolean): Boolean {
    val override = registry.snapshot(SHARE_LOOP_FEATURE_FLAG_KEY)
    if (!override.entitled) return serverEffective
    return when (override.mode) {
        DiagnosticFeatureFlagOverrideMode.ForceOn -> true
        DiagnosticFeatureFlagOverrideMode.ForceOff -> false
        DiagnosticFeatureFlagOverrideMode.Server -> serverEffective
    }
}

/** The host's share-out platform, or null where there is no system share sheet for it (desktop). */
internal fun currentRaftShareOutPlatform(): RaftShareOutPlatform? =
    runCatching { raftShareOutPlatform(currentFeatureFlagClientContext().os) }.getOrNull()

/**
 * Whether the share loop is on for [serverId] on this page: the server flag (latched, as every
 * gated feature) with the diagnostics override on top. False without a server, and where the
 * page has no shared stores (a fixture).
 */
@Composable
internal fun rememberShareLoopEnabled(serverId: ServerId?): Boolean {
    val pager = LocalActivity.current.getPager() as Pager
    val sharedStores =
        remember(pager.pagerId) { runCatching { RaftSharedStoreRegistry.get(Utils.bridgeModule(pager.pagerId)) }.getOrNull() }
            ?: return false
    val server = serverId?.takeIf { it.value.isNotBlank() }
    val serverEffective = rememberServerFeatureLatched(sharedStores.serverFeatureFlags, server, SHARE_LOOP_FEATURE_FLAG_KEY)
    val overrideRevision by
        ProcessDiagnosticFeatureFlagOverrides.registry.revisionFor(SHARE_LOOP_FEATURE_FLAG_KEY).collectAsState()
    return remember(sharedStores, serverEffective, overrideRevision, server?.value) {
        server != null && resolveShareLoopEnabled(ProcessDiagnosticFeatureFlagOverrides.registry, serverEffective)
    }
}
