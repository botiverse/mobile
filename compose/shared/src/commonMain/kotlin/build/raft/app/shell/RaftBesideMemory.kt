package build.raft.app.shell

import build.raft.app.RaftAdaptiveDetailIdentity
import build.raft.app.RaftTaskConversationChannelKind
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.get
import build.raft.app.has
import build.raft.app.matches
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.settings.matches

/*
 * What was open beside a conversation on the wide layout, remembered per conversation (web
 * docs/frontend/beside-memory.md and packages/web/src/store/besideMemory.ts, botiverse/slock staging 51c1f706a).
 * The shell closes its profile and Artifact columns when another conversation is picked (RaftWideSideColumns.kt), and
 * the reply thread's column belongs to the detail it opened beside (AdaptiveSideThread.kt): this memory opens again
 * what was open beside the conversation entered.
 *
 * The rules, as pure functions (this file); the device's storage is RaftBesideMemoryRepository.kt; the shell's wiring
 * (what shows, what opens, the visibility checks) is AppShellBesideMemoryEffects.kt.
 * - Remembered per person, server and conversation, on this device only: the open reply thread (its parent message),
 *   the open Artifact and its pinned version, the open profile (an agent's or a person's) with an agent profile's tab,
 *   and which of them is in front. Not widths, the Tasks / Files cards, dialogs, scroll positions or any other live
 *   state of a pane.
 * - Remembered when something opens and when it closes (closing forgets it), never on leaving: [RaftBesideVisit].
 * - Restored on entering a conversation, behind what the entry itself names: a notification or a link to a thread
 *   keeps that thread and in front, the remembered Artifact and profile open behind it ([raftPlanBesideRestore]).
 * - Checked first: a thing found gone is forgotten and never shown, one with no answer stays closed this time and
 *   remembered ([RaftBesideVisit.restoreStep]).
 * - Only beside a conversation's own page on the wide layout: Chat's and the DMs tab's detail
 *   ([raftBesideConversationOf]). Not Activity's or Search's detail slot, Saved, Drafts or the Tasks page; nothing on a
 *   phone or the narrow layout.
 *
 * A computer's profile is not a kind here: no computer page opens beside a conversation (RaftPaneRoles.kt).
 */

/** The kinds of thing open beside a conversation, front to back where nothing says otherwise (web `BESIDE_KINDS`). */
internal enum class RaftBesideKind(val wireName: String) {
    Thread("thread"),
    Artifact("artifact"),
    Profile("profile");

    companion object {
        fun fromWireName(value: String?): RaftBesideKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** Whose profile is open beside a conversation (web `agent:` / `human:`). */
internal enum class RaftBesideProfileKind(val wireName: String) {
    Agent("agent"),
    Human("human");

    companion object {
        fun fromWireName(value: String?): RaftBesideProfileKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** The open profile: an agent's or a person's, by id. */
internal data class RaftBesideProfile(val kind: RaftBesideProfileKind, val id: String) {
    /** As the storage writes it (web `?profile=`): `agent:<id>`, `human:<id>`. */
    val wireValue: String get() = "${kind.wireName}:$id"

    companion object {
        /** `agent:<id>` or `human:<id>` with a well-formed id; null for anything else (a computer's, an external author's). */
        fun fromWireValue(value: String?): RaftBesideProfile? {
            val text = value ?: return null
            val at = text.indexOf(':')
            if (at <= 0) return null
            val kind = RaftBesideProfileKind.fromWireName(text.substring(0, at)) ?: return null
            val id = text.substring(at + 1).takeIf { isRaftBesideId(it) } ?: return null
            return RaftBesideProfile(kind, id)
        }
    }
}

/** The open Artifact as the viewer names it (`pt_…` or a short id) and its pinned version (null: the latest). */
internal data class RaftBesideArtifact(val id: String, val version: Int? = null)

/**
 * What is open beside a conversation (web `BesideState`): the reply thread by its parent message ([thread]; the thread
 * is always the conversation's own), the Artifact, the profile with an agent profile's tab ([agentTab]), and which of
 * them is in [front]. Values only, compared by value: two states are the same when what is open, its tab and its front
 * are (web `sameBeside`).
 */
internal data class RaftBesideState(
    val thread: String? = null,
    val artifact: RaftBesideArtifact? = null,
    val profile: RaftBesideProfile? = null,
    val agentTab: String? = null,
    val front: RaftBesideKind? = null,
) {
    /** The kinds open, in [RaftBesideKind] order (web `besideKindsOf`). */
    val kinds: List<RaftBesideKind> get() = RaftBesideKind.entries.filter { has(it) }

    /** Nothing open (web `isBesideEmpty`): a conversation with nothing remembered has no entry. */
    val isEmpty: Boolean get() = thread == null && artifact == null && profile == null

    fun has(kind: RaftBesideKind): Boolean =
        when (kind) {
            RaftBesideKind.Thread -> thread != null
            RaftBesideKind.Artifact -> artifact != null
            RaftBesideKind.Profile -> profile != null
        }
}

/** Ids as the memory keeps them (web `ID`), so one person's or server's key is never a prefix of another's. */
private val RaftBesideIdPattern = Regex("[0-9A-Za-z_-]+")

/** An agent profile's tab (web `TAB`): `profile`, `activity`, `integrations`, … */
private val RaftBesideTabPattern = Regex("[0-9A-Za-z_-]{1,64}")

internal fun isRaftBesideId(value: String?): Boolean = value != null && RaftBesideIdPattern.matches(value)

/**
 * Only the well-formed parts (web `normalizeBeside`): ids as [isRaftBesideId] reads them, a pinned version only when it
 * is a positive whole number (else the latest), a tab only with an agent's profile, and a front only among what is open
 * (else the first open one in [RaftBesideKind] order).
 */
internal fun RaftBesideState.normalized(): RaftBesideState {
    val openThread = thread?.takeIf { isRaftBesideId(it) }
    val openArtifact =
        artifact?.takeIf { isRaftBesideId(it.id) }?.let { RaftBesideArtifact(it.id, it.version?.takeIf { version -> version > 0 }) }
    val openProfile = profile?.takeIf { isRaftBesideId(it.id) }
    val tab = agentTab?.takeIf { openProfile?.kind == RaftBesideProfileKind.Agent && RaftBesideTabPattern.matches(it) }
    val open = RaftBesideState(thread = openThread, artifact = openArtifact, profile = openProfile, agentTab = tab)
    return open.copy(front = front?.takeIf { open.has(it) } ?: open.kinds.firstOrNull())
}

/** Whether [kind] shows the same thing in both (the profile without its tab, web `sameValue`). */
private fun RaftBesideState.sameAs(other: RaftBesideState, kind: RaftBesideKind): Boolean =
    when (kind) {
        RaftBesideKind.Thread -> thread == other.thread
        RaftBesideKind.Artifact -> artifact == other.artifact
        RaftBesideKind.Profile -> profile == other.profile
    }

/**
 * What to remember once the shell shows [current], having shown [previous] (web `nextBesideState`): [picked] (a kind
 * the person brought in front) is in front if it is open; else whatever just opened or changed; else what was in
 * front, if still open.
 */
internal fun raftNextBesideState(
    previous: RaftBesideState?,
    current: RaftBesideState,
    picked: RaftBesideKind? = null,
): RaftBesideState {
    val before = previous ?: RaftBesideState()
    val open = current.kinds
    val opened = open.filter { !before.sameAs(current, it) }
    val front = picked?.takeIf { it in open } ?: opened.firstOrNull() ?: before.front?.takeIf { it in open }
    return current.copy(front = front).normalized()
}

/** A restore: the remembered things to check, then open ([restore]), and what is in [front] once they are open. */
internal data class RaftBesideRestorePlan(val restore: RaftBesideState, val front: RaftBesideKind?)

/**
 * The entry wins (web `planBesideRestore`): every kind the entry names ([named]: the thread a notification or a link
 * opened, a profile or an Artifact) is kept as it is and stays in front; the remembered kinds it does not name are
 * restored behind it. An entry that names nothing restores the remembered state as it was. [threadTaken]: the entry
 * has the thread's place for something else (a message of the conversation it focuses, web `?msg=`), so no remembered
 * thread is restored; it stays remembered.
 */
internal fun raftPlanBesideRestore(
    remembered: RaftBesideState?,
    named: RaftBesideState,
    threadTaken: Boolean = false,
): RaftBesideRestorePlan {
    val kept = (remembered ?: RaftBesideState()).normalized()
    val restore =
        RaftBesideState(
            thread = kept.thread?.takeIf { named.thread == null && !threadTaken },
            artifact = kept.artifact?.takeIf { named.artifact == null },
            profile = kept.profile?.takeIf { named.profile == null },
            // The entry's profile keeps its own tab: the remembered one belongs to the remembered profile.
            agentTab = kept.agentTab?.takeIf { named.profile == null },
        )
    val restored = restore.kinds
    val front = named.kinds.firstOrNull() ?: kept.front?.takeIf { it in restored } ?: restored.firstOrNull()
    return RaftBesideRestorePlan(restore, front)
}

/**
 * Only [kinds] of [state] (a profile with its tab); in front its front if it is one of them, else the first (web
 * `onlyBesideKinds`).
 */
internal fun raftOnlyBesideKinds(state: RaftBesideState?, kinds: Collection<RaftBesideKind>): RaftBesideState =
    raftWithoutBesideKinds(state ?: RaftBesideState(), RaftBesideKind.entries.filter { it !in kinds })

/**
 * [state] without [kinds] (closed, or found no longer visible): a profile goes with its tab, and the front moves on
 * (web `withoutBesideKinds`).
 */
internal fun raftWithoutBesideKinds(state: RaftBesideState, kinds: Collection<RaftBesideKind>): RaftBesideState {
    val withoutProfile = RaftBesideKind.Profile in kinds
    return state
        .copy(
            thread = if (RaftBesideKind.Thread in kinds) null else state.thread,
            artifact = if (RaftBesideKind.Artifact in kinds) null else state.artifact,
            profile = if (withoutProfile) null else state.profile,
            agentTab = if (withoutProfile) null else state.agentTab,
        )
        .normalized()
}

/** Whether a remembered thing is still visible to the person (web `BesideVisibility`). */
internal enum class RaftBesideVisibility {
    /** Open it. */
    Visible,

    /** A final answer (deleted, access removed, left the conversation, a 400 / 403 / 404): forgotten, never shown. */
    Gone,

    /** No answer (offline, a 5xx, a list still loading, too slow): not opened in this visit, and still remembered. */
    Unknown,
}

/** How long a visibility request may take before its thing stays closed for this visit (web `BESIDE_VISIBILITY_TIMEOUT_MS`). */
internal const val RAFT_BESIDE_VISIBILITY_TIMEOUT_MILLIS: Long = 4_000L

/**
 * What a restore does once its answers are in: open [open] (nothing when it is empty), with [front] in front (the pick
 * of the visit's next [RaftBesideVisit.shows]); [visit] no longer holds what was found gone.
 */
internal data class RaftBesideRestoreStep(
    val visit: RaftBesideVisit,
    val open: RaftBesideState,
    val front: RaftBesideKind?,
)

/**
 * One visit to a conversation, from entering it to leaving it (web useBesideMemory's `Visit`): what the shell showed
 * beside it last ([shown], with what is in front), and what is remembered but has not shown in this visit ([held]: a
 * thing not restored because its visibility had no answer, a thread whose place the entry took), kept as it was until a
 * thing of its kind is shown in its place, with what was remembered in front on entering ([heldFront]).
 *
 * Only two things forget a remembered thing: the person closes it (it showed in this visit, then no longer), or it is
 * found no longer visible. A visit ends without a write: the shell closing the columns of a conversation it left is no
 * step of it.
 */
internal data class RaftBesideVisit(
    val shown: RaftBesideState = RaftBesideState(),
    val held: RaftBesideState = RaftBesideState(),
    val heldFront: RaftBesideKind? = null,
) {
    /** What to remember now: [held] with [shown] over it; in front what is shown in front, else what was remembered in front. */
    val remembered: RaftBesideState
        get() =
            RaftBesideState(
                thread = shown.thread ?: held.thread,
                artifact = shown.artifact ?: held.artifact,
                profile = shown.profile ?: held.profile,
                agentTab = if (shown.profile != null) shown.agentTab else held.agentTab,
                front = shown.front ?: heldFront,
            ).normalized()

    /**
     * The shell shows [named] beside the conversation now (an open, a close, a restore's opens); [picked]: the kind the
     * person brought in front, or the one a restore puts there. A kind shown replaces the one held.
     */
    fun shows(named: RaftBesideState, picked: RaftBesideKind? = null): RaftBesideVisit =
        copy(shown = raftNextBesideState(shown, named, picked), held = raftWithoutBesideKinds(held, named.kinds))

    /** On entering: what was remembered and the entry does not name, to check and open behind it. */
    fun restorePlan(named: RaftBesideState, threadTaken: Boolean = false): RaftBesideRestorePlan =
        raftPlanBesideRestore(held.copy(front = heldFront), named, threadTaken)

    /**
     * Once [answers] for [plan] are in (web useBesideMemory `restore`), on what the shell shows now ([now]; [threadTaken]:
     * the thread's place is taken now): only the kinds still held as the plan restores them (one the person opened by
     * hand meanwhile is theirs) and not shown now are opened, those answered [RaftBesideVisibility.Visible]. Those found
     * [RaftBesideVisibility.Gone] are forgotten; those with no answer stay held, for the next visit to ask again. In
     * front: what shows now, else the plan's front, else the first opened.
     */
    fun restoreStep(
        plan: RaftBesideRestorePlan,
        answers: Map<RaftBesideKind, RaftBesideVisibility>,
        now: RaftBesideState,
        threadTaken: Boolean = false,
    ): RaftBesideRestoreStep {
        val waiting = plan.restore.kinds.filter { raftOnlyBesideKinds(held, listOf(it)) == raftOnlyBesideKinds(plan.restore, listOf(it)) }
        val gone = waiting.filter { answers[it] == RaftBesideVisibility.Gone }
        val opening =
            waiting.filter { kind ->
                answers[kind] == RaftBesideVisibility.Visible && !now.has(kind) && !(kind == RaftBesideKind.Thread && threadTaken)
            }
        val front =
            if (opening.isEmpty()) null else now.kinds.firstOrNull() ?: plan.front?.takeIf { it in opening } ?: opening.first()
        return RaftBesideRestoreStep(
            visit = copy(held = raftWithoutBesideKinds(held, gone)),
            open = raftOnlyBesideKinds(plan.restore, opening),
            front = front,
        )
    }

    companion object {
        /** Entering a conversation whose entry shows [named], with [remembered] for it: what the entry names shows, the rest is held. */
        fun enter(remembered: RaftBesideState?, named: RaftBesideState): RaftBesideVisit {
            val kinds = named.kinds
            return RaftBesideVisit(
                shown = raftOnlyBesideKinds(remembered, kinds),
                held = raftWithoutBesideKinds(remembered ?: RaftBesideState(), kinds),
                heldFront = remembered?.front,
            ).shows(named)
        }
    }
}

/**
 * The conversation beside which the memory applies, as the wide layout shows it ([raftBesideConversationOf]): its
 * server and id ([conversationId], a channel's or a DM's), what the entry itself names beside it ([named]: the reply
 * thread a notification or a link opened in the detail) and whether the entry takes the thread's place ([threadTaken]:
 * it focuses a message of the conversation).
 */
internal data class RaftBesideConversation(
    val serverId: String,
    val conversationId: String,
    val named: RaftBesideState = RaftBesideState(),
    val threadTaken: Boolean = false,
) {
    /** The conversation the side columns belong to ([RaftWideSideColumns.conversation]). */
    val detail: RaftDetailConversation get() = RaftDetailConversation(serverId, conversationId)
}

/**
 * The reply thread this detail shows in its conversation's own place (a notification or a link opened it there, or the
 * side thread folded into it), by its parent message: the conversation's thread, as the side thread is.
 */
internal fun RaftAdaptiveDetailIdentity.besideMainThread(): String? =
    parentMessageId?.takeIf {
        surfaceKind == RaftThreadSurfaceKind.ReplyThread && !parentChannelId.isNullOrBlank() && isRaftBesideId(it)
    }

/** The DMs tab ([RaftWideNavEntry.surface]): its detail is a DM's own page. */
private const val RaftBesideDmsSurface = "dms"

/**
 * The conversation [shown] (what the wide layout shows, SettingsHomePage `wideNavShowing`; null off it, so nothing on a
 * phone or the narrow layout) has open on its own page: Chat's or the DMs tab's detail, a channel or a DM, or a reply
 * thread under one, which names that thread. Null for anything else: Activity's and Search's detail slot, Saved, Drafts,
 * the Tasks page, a task's conversation.
 */
internal fun raftBesideConversationOf(shown: RaftWideNavEntry?): RaftBesideConversation? {
    val entry = shown ?: return null
    if (entry.surface != RaftRailChatTab && entry.surface != RaftBesideDmsSurface) return null
    val detail = entry.detail as? RaftWideNavDetail.Conversation ?: return null
    val identity = detail.identity
    if (identity.serverId != entry.serverId) return null
    if (detail.route.channelKind.normalizedThreadChannelKind() == RaftTaskConversationChannelKind) return null
    val conversation = identity.detailConversation()
    if (!isRaftBesideId(conversation.conversationId)) return null
    return when (identity.surfaceKind) {
        RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.DirectMessage ->
            RaftBesideConversation(conversation.serverId, conversation.conversationId, threadTaken = identity.focusedMessageId != null)
        RaftThreadSurfaceKind.ReplyThread -> {
            val parent = identity.besideMainThread() ?: return null
            val named = RaftBesideState(thread = parent).normalized()
            RaftBesideConversation(conversation.serverId, conversation.conversationId, named = named)
        }
        else -> null
    }
}
