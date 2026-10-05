package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.data.SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags

/**
 * No-apply normalized reaction observation inside the existing `sync_core_messages_v1` shadow rollout. It
 * reuses the canonical decode already attached to accepted realtime message events, before legacy sink
 * fan-out. The lane has no store, cursor, cache, overlay, or UI reference, so its only effect is bounded
 * diagnostics. There is deliberately no second feature flag or rollout track.
 */
internal class SyncCoreReactionShadowLane(
    private val featureFlags: SharedServerFeatureFlags,
    private val logger: (String) -> Unit,
) {
    fun isEnabled(): Boolean =
        featureFlags.isEnabledForAnyResolvedServer(SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY)

    fun observe(
        resolvedServerId: String,
        decoded: CanonicalMessageCarry,
    ): NormalizedReactionIngress? {
        if (!isEnabled()) return null
        return try {
            when (
                val outcome = normalizeLegacyReactionRoster(
                    context = ReactionNormalizationContext(
                        serverId = resolvedServerId,
                        messageId = decoded.row.id,
                        parentScopeKey = ReactionParentScopeKey(
                            serverId = resolvedServerId,
                            scopeId = decoded.row.channelId,
                            scopeKind = SHADOW_SCOPE_KIND,
                        ),
                        authority = ReactionIngressAuthority.SharedRoom,
                    ),
                    reactions = decoded.row.reactions.map { it.toLegacyReactionRosterDto() },
                )
            ) {
                is ReactionNormalizationOutcome.Normalized -> {
                    if (outcome.value.violations.isNotEmpty()) {
                        val reportedViolations = outcome.value.violations
                            .take(MAX_REPORTED_VIOLATIONS)
                            .joinToString(",") { it.kind.name }
                        logger(
                            "sync_core_reaction_shadow result=violations serverId=$resolvedServerId " +
                                "msg=${decoded.row.id} violations=$reportedViolations " +
                                "violationCount=${outcome.value.violations.size}",
                        )
                    }
                    outcome.value
                }
                is ReactionNormalizationOutcome.Rejected -> {
                    logger(
                        "sync_core_reaction_shadow result=rejected serverId=$resolvedServerId " +
                            "msg=${decoded.row.id} reason=${outcome.failure.name}",
                    )
                    null
                }
            }
        } catch (throwable: Throwable) {
            logFailure(resolvedServerId, decoded.row.id, throwable)
            null
        }
    }

    fun logFailure(serverId: String, messageId: String, throwable: Throwable) {
        runCatching {
            logger(
                "sync_core_reaction_shadow result=failed serverId=$serverId msg=$messageId " +
                    "error=${throwable::class.simpleName}",
            )
        }
    }

    companion object {
        private const val SHADOW_SCOPE_KIND = "channel"
        private const val MAX_REPORTED_VIOLATIONS = 8
    }
}
