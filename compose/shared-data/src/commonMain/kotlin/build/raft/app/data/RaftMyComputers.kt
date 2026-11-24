package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/*
 * "My Computers first" (artin, #triage:#332; web botiverse/slock #14479,
 * `components/layout/sidebarMachineOrder.ts`): every list of Computers puts the viewer's own
 * first. A phone is never a Computer itself, so unlike Raft Desktop there is no "this device"
 * to pin ahead of them: the order is the viewer's own, then the rest, each keeping the server's
 * order.
 */

/**
 * Web `isMyComputer`: a Computer the viewer attached ([attachedByCurrentUser], server-derived) or
 * one Raft records them as the owner of ([ownerUserId]; null is a Public Computer, nobody's).
 * Without a signed-in [userId] only the attach flag counts, never a null owner.
 */
@RaftCoreInternalApi fun raftIsMyComputer(attachedByCurrentUser: Boolean, ownerUserId: String?, userId: String?): Boolean {
    if (attachedByCurrentUser) return true
    val me = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return ownerUserId?.trim() == me
}

@RaftCoreInternalApi fun ComputerSummary.isMyComputer(userId: String?): Boolean = raftIsMyComputer(attachedByCurrentUser, ownerUserId, userId)

@RaftCoreInternalApi fun SettingsComputerSnapshot.isMyComputer(userId: String?): Boolean = raftIsMyComputer(attachedByCurrentUser, ownerUserId, userId)

/** Web `orderSidebarMachineIds` (no pin): [mine] first, then the rest, both in the order given (stable). */
@RaftCoreInternalApi fun <T> List<T>.raftMineFirst(mine: (T) -> Boolean): List<T> {
    val (own, rest) = partition(mine)
    return own + rest
}
