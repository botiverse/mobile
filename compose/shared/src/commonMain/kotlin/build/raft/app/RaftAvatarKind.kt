package build.raft.app

import androidx.compose.ui.graphics.Color
import build.raft.app.model.MemberKind
import build.raft.app.model.appAvatarWireValueOrNull
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.designsystem.components.RaftAvatarType

/**
 * Whose face an avatar is: web `AvatarSlot`'s `type`. It decides what [RaftAppAvatar] draws when
 * there is no usable image, and when an image fails to load, so no page picks its own fallback
 * (botiverse/slock `packages/web/src/components/ui/AvatarSlot.tsx`, `agent/PixelAvatar.tsx`
 * `AgentAvatar`, `member/PersonAvatar.tsx`):
 *
 * - [Agent] (`AgentAvatar`): the agent's pixel avatar or uploaded image; with none, an unknown
 *   value, or an image that fails, the default pixel robot. Never initials.
 * - [Human] (`PersonAvatar`): the person's avatar stored by Raft; with none, any other value (an
 *   old social-provider picture, a pixel key), or an image that fails, the placeholder person.
 *   Never initials.
 * - [Labeled] (`type="app"` / `type="server"`): someone Raft has no kind for (an external
 *   participant, an App, a sender whose kind is not known yet): their image, else the label's
 *   initials, else the placeholder person.
 */
enum class RaftAvatarKind {
    Agent,
    Human,
    Labeled,
}

/** The kind of a member's face; any other or unknown kind keeps its label ([RaftAvatarKind.Labeled]). */
internal fun raftAvatarKind(kind: MemberKind?): RaftAvatarKind =
    when (kind) {
        MemberKind.Agent -> RaftAvatarKind.Agent
        MemberKind.Human -> RaftAvatarKind.Human
        MemberKind.System, MemberKind.External, null -> RaftAvatarKind.Labeled
    }

/**
 * The wire value [RaftAppAvatar] draws for [kind]: [avatarWireValueForAvatarSlot]'s rule, the
 * one shared avatar-slot rule. An agent always has a face (its own, else `pixel:robot`); a
 * person only an avatar Raft stores (null: the placeholder); a labeled face any non-retired value.
 */
internal fun raftAvatarFaceWireValue(kind: RaftAvatarKind, avatarWireValue: String?): String? =
    when (kind) {
        RaftAvatarKind.Agent -> avatarWireValueForAvatarSlot(MemberKind.Agent, id = "", avatarWireValue = avatarWireValue)
        RaftAvatarKind.Human -> avatarWireValueForAvatarSlot(MemberKind.Human, id = "", avatarWireValue = avatarWireValue)
        RaftAvatarKind.Labeled -> avatarWireValue?.appAvatarWireValueOrNull()
    }

/** What a face shows without an image, or when its image fails ([raftAvatarFallbackFace]). */
internal enum class RaftAvatarFallbackFace {
    /** The default pixel robot (web `PixelAvatar` with `DEFAULT_AVATAR_KEY`). */
    DefaultPixel,

    /** raft-ui's placeholder person (web lucide `User`). */
    Placeholder,

    /** The label's 1–2 initials. */
    Initials,
}

/** The fallback of [kind], with [label]'s [initials] at the avatar's step (empty: no letter to show). */
internal fun raftAvatarFallbackFace(kind: RaftAvatarKind, initials: String): RaftAvatarFallbackFace =
    when (kind) {
        RaftAvatarKind.Agent -> RaftAvatarFallbackFace.DefaultPixel
        RaftAvatarKind.Human -> RaftAvatarFallbackFace.Placeholder
        RaftAvatarKind.Labeled ->
            if (initials.isEmpty()) RaftAvatarFallbackFace.Placeholder else RaftAvatarFallbackFace.Initials
    }

/** raft-ui Avatar `type`: an agent's sticker sits on `inactive` until its face covers it, everyone else on `palette-lavender`. */
internal val RaftAvatarKind.fill: Color
    get() = if (this == RaftAvatarKind.Agent) RaftColor.Inactive else RaftColor.AccentLavender

/** The design-system sticker [RaftAvatarType] for [this] kind's frame. */
internal val RaftAvatarKind.stickerType: RaftAvatarType
    get() = if (this == RaftAvatarKind.Agent) RaftAvatarType.Agent else RaftAvatarType.Human
