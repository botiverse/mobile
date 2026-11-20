package build.raft.app

import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile

/** A person whose server role is Guest (slock shared `isGuestRole`). */
internal fun MemberProfile.isServerGuest(): Boolean =
    kind == MemberKind.Human && serverRole?.trim()?.equals("guest", ignoreCase = true) == true

/**
 * Whether a person may be offered as a candidate to add to a channel (web `isAddableToChannel`,
 * botiverse/slock #15354). A Guest belongs to its one bound channel: the server refuses it in any
 * other channel (403 guest_other_channel) and in a joint channel, so the add picker leaves it out
 * there. Guests already in the channel still show in its member list; this only narrows who can
 * be added. [channelType] is the channel's kind (`joint` for a joint channel).
 */
internal fun MemberProfile.isAddableToChannel(channelId: String?, channelType: String?): Boolean {
    if (!isServerGuest()) return true
    val id = channelId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return !channelType.equals("joint", ignoreCase = true) && guestChannel?.id == id
}

/**
 * Whether a person may be offered as an initial member of a channel being created (web
 * `isAddableToNewChannel`, botiverse/slock #15362): a new channel is never a Guest's bound one, so
 * the server refuses any Guest among its initial members (403 guest_other_channel).
 */
internal fun MemberProfile.isAddableToNewChannel(): Boolean = !isServerGuest()
