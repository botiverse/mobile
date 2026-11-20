package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
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

/** The server's answer with the diagnostics override applied (as [resolveActivityStableOrderEnabled]). */
internal fun resolveShareLoopEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean =
    registry.decide(
        key = SHARE_LOOP_FEATURE_FLAG_KEY,
        serverEffective = serverEffective,
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId,
            ),
    ).finalEffective

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
    val accountId by
        produceState("", sharedStores) {
            value = runCatching { sharedStores.authStore.readSession()?.user?.id }.getOrNull().orEmpty()
        }
    return remember(sharedStores, serverEffective, overrideRevision, server?.value, accountId) {
        server != null &&
            resolveShareLoopEnabled(
                registry = ProcessDiagnosticFeatureFlagOverrides.registry,
                serverEffective = serverEffective,
                environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                accountId = accountId,
                serverId = server.value,
            )
    }
}
