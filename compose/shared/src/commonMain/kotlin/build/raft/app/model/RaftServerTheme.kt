package build.raft.app.model

import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * Per-member, per-server Raft theme (design/foundations/themes.md): each person picks a theme for
 * each server they are in; it is stored on their membership. Contract: botiverse/slock
 * packages/shared/src/raftThemes.ts (RAFT_THEME_IDS, PRO_RAFT_THEME_IDS, effectiveRaftTheme) and
 * packages/web/src/theme/serverTheme.ts (serverThemeLook).
 */

/**
 * A member's stored theme choice from its wire id. The server keeps Raft Yellow as `null` and
 * answers `null` for it; `"raft"`, `null` and any id this build does not know all mean the
 * default, which this returns as `null` so the default has one representation.
 */
internal fun raftThemeChoiceFromWire(wire: String?): RaftThemeId? =
    wire?.let { RaftThemeId.fromWire(it) }?.takeUnless { it == RaftThemeId.Raft }

/** Encodes a [RaftThemeId] as its wire id; an unknown id decodes to [RaftThemeId.Raft]. */
object RaftThemeIdWireSerializer : KSerializer<RaftThemeId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("build.raft.app.model.RaftThemeIdWire", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RaftThemeId) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): RaftThemeId = RaftThemeId.fromWire(decoder.decodeString())
}

/**
 * What a member sees on one server: the effective theme and whether its decorations are on.
 * Web `ServerThemeLook` (serverTheme.ts).
 */
data class RaftServerThemeLook(
    val theme: RaftThemeId,
    val decor: Boolean,
) {
    companion object {
        /** Raft Yellow with decorations on: no server, signed out, or nothing chosen. */
        val Default: RaftServerThemeLook = RaftServerThemeLook(RaftThemeId.Raft, decor = true)
    }
}

/** Web `isFreePlan` in serverTheme.ts: no plan reads as Free. */
internal fun isFreeThemePlan(plan: String?): Boolean = plan.isNullOrBlank() || plan == "free"

/**
 * slock `effectiveRaftTheme(choice, serverPlanIsFree)`: the member's choice, except a Pro theme
 * on a Free server, which shows as Raft Yellow and comes back when the server is on a paid plan.
 */
internal fun raftServerThemeLook(choice: RaftThemeId?, decor: Boolean, plan: String?): RaftServerThemeLook =
    RaftServerThemeLook(
        theme = choice?.takeUnless { it.isPro && isFreeThemePlan(plan) } ?: RaftThemeId.Raft,
        decor = decor,
    )

fun ServerSummary.themeLook(): RaftServerThemeLook = raftServerThemeLook(theme, themeDecor, plan)
