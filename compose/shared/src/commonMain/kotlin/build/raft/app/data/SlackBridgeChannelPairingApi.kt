package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pairing one Raft channel with a Slack channel from channel settings, the part of the Slack
 * Bridge control plane web EditChannelDialog uses (`ChannelSlackBridgeField`,
 * `applyChannelSlackBridgeSelection`). Contract: botiverse/slock
 * `packages/shared/src/slackBridgeProvisioning.ts` (`slackBridgeProvisioningResponseSchema`,
 * `slackBridgeChannelPairsRequestSchema`, `slackBridgeChannelPairRemovalsRequestSchema`) and
 * `packages/server/src/routes/slackBridge.ts`:
 *
 * - `GET slack-bridge/provisioning` → `{ protocolVersion: 1, snapshot, oauthAuthority }`
 * - `PUT slack-bridge/provisioning/channel-pairs` `{ pairs: [{ raftChannelId, slackChannelId }] }`
 *   (the whole desired set)
 * - `DELETE slack-bridge/provisioning/channel-pairs`
 *   `{ pairs: [{ raftChannelId, slackChannelId, expectedBindingEpoch }] }`
 * - `POST slack-bridge/provisioning/preflight`, then `POST slack-bridge/provisioning/enable`
 *
 * Connecting the Slack workspace itself is an OAuth redirect and stays on the web; channel
 * settings only offers the pick once the workspace is at the `health` stage. Mutations need a
 * server owner or admin; the server refuses anyone else.
 */
internal const val SLACK_BRIDGE_FEATURE_FLAG_KEY: String = "slack_bridge_v0"

internal const val SLACK_BRIDGE_STAGE_HEALTH: String = "health"

@Serializable
internal data class SlackBridgeProvisioningWireResponse(
    val protocolVersion: Int,
    val snapshot: SlackBridgeSnapshotWire,
)

@Serializable
internal data class SlackBridgeSnapshotWire(
    val stage: String,
    val workspaceName: String? = null,
    val slackChannels: List<SlackBridgeSlackChannelWire> = emptyList(),
    val channelPairs: List<SlackBridgeChannelPairWire> = emptyList(),
    val preflight: SlackBridgePreflightWire? = null,
)

@Serializable
internal data class SlackBridgeSlackChannelWire(
    val id: String,
    val name: String,
    val privacyClass: String? = null,
    val isMember: Boolean? = null,
)

@Serializable
internal data class SlackBridgeChannelPairWire(
    val raftChannelId: String,
    val slackChannelId: String,
    val bindingEpoch: Long? = null,
)

@Serializable
internal data class SlackBridgePreflightWire(val state: String)

@Serializable
internal data class SlackBridgeChannelPairBody(val raftChannelId: String, val slackChannelId: String)

@Serializable
internal data class SlackBridgeChannelPairsRequest(val pairs: List<SlackBridgeChannelPairBody>)

@Serializable
internal data class SlackBridgeChannelPairRemovalBody(
    val raftChannelId: String,
    val slackChannelId: String,
    val expectedBindingEpoch: Long,
)

@Serializable
internal data class SlackBridgeChannelPairRemovalsRequest(val pairs: List<SlackBridgeChannelPairRemovalBody>)

/** `POST feature-flags/evaluate` for [SLACK_BRIDGE_FEATURE_FLAG_KEY], platform "web" like the web gate. */
@Serializable
internal data class SlackBridgeFlagEvaluateBody(
    val serverId: String,
    val keys: List<String> = listOf(SLACK_BRIDGE_FEATURE_FLAG_KEY),
    val platform: String = "web",
)

@Serializable
private data class SlackBridgeFlagEvaluation(val key: String, val enabled: Boolean = false)

@Serializable
private data class SlackBridgeFlagEvaluateResponse(val evaluations: List<SlackBridgeFlagEvaluation> = emptyList())

private val slackBridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** One Slack channel the Raft channel could pair with. */
data class ChannelSlackBridgeOption(
    val slackChannelId: String,
    val name: String,
    /** Web `channelMembershipRequiredOption`: the Raft app is not in the Slack channel yet. */
    val membershipRequired: Boolean,
    /** Web `alreadyPairedOption`: another Raft channel holds it. */
    val pairedElsewhere: Boolean,
)

/** The channel's Slack Bridge pick (web `SlackBridgeEditor`): offered only at the `health` stage. */
data class ChannelSlackBridgeState(
    val options: List<ChannelSlackBridgeOption>,
    /** The Slack channel paired with this Raft channel now; null when not connected. */
    val pairedSlackChannelId: String?,
)

/**
 * Web `ChannelSlackBridgeField` options: Slack channels of the channel's visibility (or of no
 * stated privacy), each marked when it needs the Raft app invited or is paired elsewhere.
 */
internal fun SlackBridgeSnapshotWire.toChannelSlackBridgeState(
    raftChannelId: String,
    visibility: String,
): ChannelSlackBridgeState? {
    if (stage != SLACK_BRIDGE_STAGE_HEALTH) return null
    val pairedBySlackId = channelPairs.associate { it.slackChannelId to it.raftChannelId }
    val current = channelPairs.firstOrNull { it.raftChannelId == raftChannelId }?.slackChannelId
    return ChannelSlackBridgeState(
        options =
            slackChannels
                .filter { it.privacyClass == null || it.privacyClass == visibility }
                .map { channel ->
                    val pairedRaft = pairedBySlackId[channel.id]
                    ChannelSlackBridgeOption(
                        slackChannelId = channel.id,
                        name = channel.name,
                        membershipRequired = channel.isMember != true,
                        pairedElsewhere = pairedRaft != null && pairedRaft != raftChannelId,
                    )
                },
        pairedSlackChannelId = current,
    )
}

internal fun parseSlackBridgeProvisioning(bodyText: String): SlackBridgeSnapshotWire? =
    runCatching {
        slackBridgeJson.decodeFromString(SlackBridgeProvisioningWireResponse.serializer(), bodyText)
            .takeIf { it.protocolVersion == 1 }
            ?.snapshot
    }.getOrNull()

internal fun parseSlackBridgeFlagEnabled(bodyText: String): Boolean =
    runCatching {
        slackBridgeJson.decodeFromString(SlackBridgeFlagEvaluateResponse.serializer(), bodyText)
            .evaluations
            .firstOrNull { it.key == SLACK_BRIDGE_FEATURE_FLAG_KEY }
            ?.enabled == true
    }.getOrDefault(false)

internal fun encodeSlackBridgeChannelPairs(pairs: List<SlackBridgeChannelPairBody>): String =
    slackBridgeJson.encodeToString(SlackBridgeChannelPairsRequest.serializer(), SlackBridgeChannelPairsRequest(pairs))

internal fun encodeSlackBridgeChannelPairRemoval(removal: SlackBridgeChannelPairRemovalBody): String =
    slackBridgeJson.encodeToString(
        SlackBridgeChannelPairRemovalsRequest.serializer(),
        SlackBridgeChannelPairRemovalsRequest(listOf(removal)),
    )

internal fun encodeSlackBridgeFlagEvaluate(serverId: ServerId): String =
    slackBridgeJson.encodeToString(SlackBridgeFlagEvaluateBody.serializer(), SlackBridgeFlagEvaluateBody(serverId.value))

internal const val SLACK_BRIDGE_PREFLIGHT_FAILED = "slack_bridge_preflight_failed"
internal const val SLACK_BRIDGE_BINDING_UNAVAILABLE = "slack_bridge_binding_coordinates_unavailable"

internal class SlackBridgeChannelPairingRepository(
    private val networkClient: RaftNetworkClient,
    private val serverId: ServerId,
) {
    /** The flag, then the snapshot; null when the bridge is off, unreachable or not at `health`. */
    suspend fun loadState(channelId: ChannelId, visibility: String): ChannelSlackBridgeState? {
        if (!flagEnabled()) return null
        val snapshot = (load() as? DataResult.Content)?.data ?: return null
        return snapshot.toChannelSlackBridgeState(channelId.value, visibility)
    }

    /**
     * Web `applyChannelSlackBridgeSelection` on a fresh snapshot: nothing when the pick is
     * unchanged; else remove the channel's current pair (by its binding epoch), then, for a new
     * pick, save the whole set plus the new pair, run preflight (it must pass) and enable.
     */
    suspend fun apply(channelId: ChannelId, visibility: String, selectedSlackChannelId: String?): DataResult<ChannelSlackBridgeState?> {
        var snapshot = (load() as? DataResult.Content)?.data ?: return failure("slack_bridge_load_failed")
        val currentPair = snapshot.channelPairs.firstOrNull { it.raftChannelId == channelId.value }
        if (currentPair?.slackChannelId == selectedSlackChannelId || (currentPair == null && selectedSlackChannelId == null)) {
            return DataResult.Content(snapshot.toChannelSlackBridgeState(channelId.value, visibility))
        }
        if (currentPair != null) {
            val epoch = currentPair.bindingEpoch ?: return failure(SLACK_BRIDGE_BINDING_UNAVAILABLE)
            snapshot =
                mutate("DELETE", "slack-bridge/provisioning/channel-pairs") {
                    encodeSlackBridgeChannelPairRemoval(
                        SlackBridgeChannelPairRemovalBody(currentPair.raftChannelId, currentPair.slackChannelId, epoch)
                    )
                }.contentOrReturn { return it }
        }
        if (selectedSlackChannelId != null) {
            val pairs =
                snapshot.channelPairs.map { SlackBridgeChannelPairBody(it.raftChannelId, it.slackChannelId) } +
                    SlackBridgeChannelPairBody(channelId.value, selectedSlackChannelId)
            mutate("PUT", "slack-bridge/provisioning/channel-pairs") { encodeSlackBridgeChannelPairs(pairs) }
                .contentOrReturn { return it }
            val preflight = mutate("POST", "slack-bridge/provisioning/preflight") { null }.contentOrReturn { return it }
            if (preflight.preflight?.state != "passed") return failure(SLACK_BRIDGE_PREFLIGHT_FAILED)
            snapshot = mutate("POST", "slack-bridge/provisioning/enable") { null }.contentOrReturn { return it }
        }
        return DataResult.Content(snapshot.toChannelSlackBridgeState(channelId.value, visibility))
    }

    private suspend fun flagEnabled(): Boolean {
        val response =
            call {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "feature-flags/evaluate",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = encodeSlackBridgeFlagEvaluate(serverId),
                )
            } ?: return false
        if (!response.isSuccessful) return false
        return response.bodyText?.let(::parseSlackBridgeFlagEnabled) == true
    }

    private suspend fun load(): DataResult<SlackBridgeSnapshotWire> =
        mutate("GET", "slack-bridge/provisioning", priority = DataRequestPriority.Visible) { null }

    private suspend fun mutate(
        method: String,
        path: String,
        priority: DataRequestPriority = DataRequestPriority.Foreground,
        body: () -> String?,
    ): DataResult<SlackBridgeSnapshotWire> {
        val response =
            call {
                networkClient.request(
                    priority = priority,
                    path = path,
                    method = method,
                    serverIdOverride = serverId,
                    bodyText = body(),
                )
            } ?: return failure("slack_bridge_network", retryable = true)
        if (!response.isSuccessful) {
            return failure(slackBridgeErrorCode(response.bodyText ?: response.errorBodyText) ?: "slack_bridge_http_${response.code}")
        }
        val snapshot = response.bodyText?.let(::parseSlackBridgeProvisioning) ?: return failure("network_parse_failed")
        return DataResult.Content(snapshot)
    }

    private suspend fun call(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun <T> failure(code: String, retryable: Boolean = false): DataResult<T> =
        DataResult.Error(ScreenError(code = code, retryable = retryable))
}

private inline fun <T> DataResult<T>.contentOrReturn(onFailure: (DataResult<Nothing>) -> Nothing): T =
    when (this) {
        is DataResult.Content -> data
        is DataResult.Error -> onFailure(this)
        is DataResult.NoPermission -> onFailure(this)
        is DataResult.Empty -> onFailure(this)
    }

@Serializable
private data class SlackBridgeErrorBody(val ok: Boolean? = null, val code: String? = null)

/** The control plane answers `{ ok: false, code }` (e.g. `slack_bridge_disabled`). */
internal fun slackBridgeErrorCode(bodyText: String?): String? =
    bodyText?.let { text ->
        runCatching { slackBridgeJson.decodeFromString(SlackBridgeErrorBody.serializer(), text).code }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }
