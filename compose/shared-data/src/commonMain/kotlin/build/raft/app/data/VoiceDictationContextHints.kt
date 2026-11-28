package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.time.Instant

// Caller-supplied voice dictation composer context (`draftBefore`, `draftAfter`) and local
// server-scoped vocabulary hints (`contactHints`) (#wg-call:#10, #wg-live:#21).
// Authoritative reference: `botiverse/slock` PR `#17322` (`7e75a60fe`) and PR `#17370` (`a904ef90c01f`):
// - `docs/contracts/voice-dictation-v2.md`
// - `packages/shared/src/voiceDictation.ts`
// - `packages/web/src/features/composer/voiceDictationLocalHints.ts`
//
// Privacy boundary: values produced here are transient per-request inputs only —
// never cached, never persisted, never logged, and never emitted in client traces.

/** Maximum Unicode code points retained from the tail of read-only composer draft before the caret. */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_DRAFT_BEFORE_CODE_POINTS: Int = 500

/** Maximum Unicode code points retained from the head of read-only composer draft after the caret/selection. */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_DRAFT_AFTER_CODE_POINTS: Int = 200

/** Maximum normalized local contact/agent/channel vocabulary hints sent on an initial permit request. */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_CONTACT_HINTS: Int = 20

/** Maximum Unicode code points per individual contact hint entry (items longer than this are dropped). */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS: Int = 64

/**
 * Normalizes read-only composer text before the caret (`#wg-call:#10`, parity with
 * `normalizeVoiceDictationDraftBefore` in `@botiverse/raft-shared`):
 * - Strips Unicode `Cc` (control) and `Cf` (format) code points EXCEPT `\n` (`U+000A`).
 *   Note that `\r` and `\t` are `Cc` and are stripped identically to `slock`.
 * - Trims leading and trailing whitespace.
 * - Retains the tail nearest the caret up to [VOICE_DICTATION_MAX_DRAFT_BEFORE_CODE_POINTS] (`500`)
 *   Unicode code points without splitting UTF-16 surrogate pairs, trimming again after slicing.
 * - Returns `null` when empty.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationDraftBefore(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    val cleaned = stripControlAndFormatCodePoints(raw, preserveNewline = true).trim()
    if (cleaned.isEmpty()) return null

    val starts = IntArray(cleaned.length)
    var codePointCount = 0
    var index = 0
    while (index < cleaned.length) {
        starts[codePointCount++] = index
        index +=
            if (
                cleaned[index].isHighSurrogate() &&
                index + 1 < cleaned.length &&
                cleaned[index + 1].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
    }
    if (codePointCount <= VOICE_DICTATION_MAX_DRAFT_BEFORE_CODE_POINTS) {
        return cleaned
    }
    val tailStart = starts[codePointCount - VOICE_DICTATION_MAX_DRAFT_BEFORE_CODE_POINTS]
    val tail = cleaned.substring(tailStart).trim()
    return tail.takeIf { it.isNotEmpty() }
}

/**
 * Extracts and normalizes the read-only composer draft immediately preceding [caretIndex] (UTF-16
 * offset into [text]). Clamps [caretIndex] safely into `0..text.length` and backs up by 1 if it
 * lands between a UTF-16 high/low surrogate pair so no dangling surrogate is ever produced.
 */
@RaftCoreInternalApi
fun extractVoiceDictationDraftBeforeCaret(
    text: String,
    caretIndex: Int,
): String? {
    if (text.isEmpty()) return null
    var end = caretIndex.coerceIn(0, text.length)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
        end -= 1
    }
    return normalizeVoiceDictationDraftBefore(text.substring(0, end))
}

/**
 * Normalizes read-only composer text immediately after the caret/selection (`#wg-live:#21`, parity
 * with `normalizeVoiceDictationDraftAfter` in `@botiverse/raft-shared`):
 * - Normalizes `\r\n` and `\r` to `\n`.
 * - Strips Unicode `Cc` (control) and `Cf` (format) code points EXCEPT `\n` (`U+000A`) and `\t` (`U+0009`).
 * - Preserves leading newlines and horizontal whitespace (only trims trailing whitespace) so
 *   downstream models can distinguish same-line mid-sentence continuation from end-of-line before a
 *   new paragraph.
 * - Retains the head nearest the cursor up to [VOICE_DICTATION_MAX_DRAFT_AFTER_CODE_POINTS] (`200`)
 *   Unicode code points without splitting UTF-16 surrogate pairs, trimming trailing whitespace again
 *   after slicing.
 * - Returns `null` when empty or whitespace-only.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationDraftAfter(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    val crNormalized =
        if ('\r' in raw) {
            raw.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            raw
        }
    val cleaned =
        stripControlAndFormatCodePoints(
            raw = crNormalized,
            preserveNewline = true,
            preserveTab = true,
        ).trimEnd()
    if (cleaned.trim().isEmpty()) return null

    var codePointCount = 0
    var index = 0
    while (index < cleaned.length && codePointCount < VOICE_DICTATION_MAX_DRAFT_AFTER_CODE_POINTS) {
        index +=
            if (
                cleaned[index].isHighSurrogate() &&
                index + 1 < cleaned.length &&
                cleaned[index + 1].isLowSurrogate()
            ) {
                2
            } else {
                1
            }
        codePointCount += 1
    }
    val head =
        if (index >= cleaned.length) {
            cleaned
        } else {
            cleaned.substring(0, index).trimEnd()
        }
    return head.takeIf { it.trim().isNotEmpty() }
}

/**
 * Extracts and normalizes the read-only composer suffix immediately following [selectionEnd]
 * (UTF-16 offset into [text], `#wg-live:#21`, parity with `extractVoiceDictationDraftAfter` in
 * `@botiverse/raft-shared`). Emitted only when [selectionEnd] is strictly before `text.length` and
 * non-whitespace text follows; advances by 1 if [selectionEnd] lands between a UTF-16 high/low
 * surrogate pair so no dangling low surrogate is ever produced.
 */
@RaftCoreInternalApi
fun extractVoiceDictationDraftAfterCaret(
    text: String,
    selectionEnd: Int,
): String? {
    if (text.isEmpty()) return null
    var start = selectionEnd.coerceIn(0, text.length)
    if (start >= text.length) return null
    if (start > 0 && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) {
        start += 1
        if (start >= text.length) return null
    }
    return normalizeVoiceDictationDraftAfter(text.substring(start))
}

/**
 * Normalizes caller-supplied recent/frequent contact, agent, and channel vocabulary hints
 * (`#wg-call:#10`, parity with `normalizeVoiceDictationContactHints` in `@botiverse/raft-shared`):
 * - Per item: collapses runs of whitespace/newlines into a single ASCII space (`" "`), strips all
 *   Unicode `Cc` (control) and `Cf` (format) code points, collapses any newly adjacent spaces, and
 *   trims.
 * - Drops empty items and items exceeding [VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS] (`64`)
 *   Unicode code points (counting code points, not UTF-16 code units).
 * - Deduplicates case-insensitively (`lowercase()`), preserving the first occurrence's casing.
 * - Caps at the first [VOICE_DICTATION_MAX_CONTACT_HINTS] (`20`) unique valid entries.
 * - Returns `null` when empty.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationContactHints(raw: Iterable<String?>?): List<String>? {
    if (raw == null) return null
    val out = ArrayList<String>(VOICE_DICTATION_MAX_CONTACT_HINTS)
    val seen = HashSet<String>()
    for (entry in raw) {
        if (entry.isNullOrEmpty()) continue
        val cleaned = normalizeSingleContactHint(entry)
        if (cleaned.isEmpty()) continue
        if (countUnicodeCodePoints(cleaned) > VOICE_DICTATION_MAX_CONTACT_HINT_CODE_POINTS) continue
        val key = cleaned.lowercase()
        if (!seen.add(key)) continue
        out.add(cleaned)
        if (out.size >= VOICE_DICTATION_MAX_CONTACT_HINTS) break
    }
    return out.takeIf { it.isNotEmpty() }
}

/** Snapshot record for a local DM conversation candidate in [VoiceDictationLocalHintSnapshot]. */
@RaftCoreInternalApi
data class VoiceDictationLocalDmHint(
    val id: String,
    val serverId: String? = null,
    val peerDisplayName: String? = null,
    val peerName: String? = null,
    val archived: Boolean = false,
    val lastActivityAt: String? = null,
)

/** Snapshot record for a local channel candidate in [VoiceDictationLocalHintSnapshot]. */
@RaftCoreInternalApi
data class VoiceDictationLocalChannelHint(
    val id: String,
    val serverId: String? = null,
    val name: String,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val isDmOrThread: Boolean = false,
    val lastActivityAt: String? = null,
)

/** Snapshot record for a local server agent candidate in [VoiceDictationLocalHintSnapshot]. */
@RaftCoreInternalApi
data class VoiceDictationLocalAgentHint(
    val id: String,
    val serverId: String? = null,
    val displayName: String? = null,
    val name: String? = null,
    val deleted: Boolean = false,
    val serviceAgent: Boolean = false,
)

/** Snapshot record for a local server human member candidate in [VoiceDictationLocalHintSnapshot]. */
@RaftCoreInternalApi
data class VoiceDictationLocalMemberHint(
    val userId: String,
    val serverId: String? = null,
    val displayName: String? = null,
    val name: String? = null,
)

/**
 * Pure in-memory snapshot of local stores for [collectVoiceDictationContactHintsFromSnapshot].
 * Strictly scoped to [serverId] (must match [currentServerId]).
 */
@RaftCoreInternalApi
data class VoiceDictationLocalHintSnapshot(
    val serverId: String,
    val currentServerId: String?,
    val currentUserId: String? = null,
    val recentConversationIds: List<String> = emptyList(),
    val dmChannels: List<VoiceDictationLocalDmHint> = emptyList(),
    val channels: List<VoiceDictationLocalChannelHint> = emptyList(),
    val agents: List<VoiceDictationLocalAgentHint> = emptyList(),
    val members: List<VoiceDictationLocalMemberHint> = emptyList(),
    /** Optional channelId -> serverId memo for defense-in-depth cross-server isolation. */
    val resolveChannelServerId: ((channelId: String) -> String?)? = null,
)

/**
 * Pure server-scoped local contact/agent/channel hint collector over an already-read snapshot of
 * in-memory stores (parity with `collectVoiceDictationContactHintsFromSnapshot` in `slock`).
 *
 * Strict isolation: returns `null` immediately when [VoiceDictationLocalHintSnapshot.serverId] is
 * blank or does not match [VoiceDictationLocalHintSnapshot.currentServerId], and drops every item
 * whose own server ID or [VoiceDictationLocalHintSnapshot.resolveChannelServerId] belongs to
 * another server.
 *
 * Priority order:
 * 1. Most recently visited / active conversations in this server (DMs -> peer displayName, peerName;
 *    channels -> name stripped of leading `#`).
 * 2. Active non-archived DMs in this server, sorted by most recent activity timestamp descending.
 * 3. Active non-deleted, non-service agents in this server (`displayName`, `name`).
 * 4. Joined non-archived channels in this server, sorted by most recent activity descending (`#` stripped).
 * 5. Server members in this server, skipping [VoiceDictationLocalHintSnapshot.currentUserId]
 *    (`displayName`, `name`).
 */
@RaftCoreInternalApi
fun collectVoiceDictationContactHintsFromSnapshot(
    snapshot: VoiceDictationLocalHintSnapshot,
): List<String>? {
    val targetServerId = snapshot.serverId.trim()
    val activeServerId = snapshot.currentServerId?.trim()
    if (targetServerId.isEmpty() || activeServerId.isNullOrEmpty() || activeServerId != targetServerId) {
        return null
    }

    val resolveServer = snapshot.resolveChannelServerId
    val dmById = LinkedHashMap<String, VoiceDictationLocalDmHint>()
    val eligibleDms = ArrayList<VoiceDictationLocalDmHint>()
    for (dm in snapshot.dmChannels) {
        if (dm.archived) continue
        if (!itemBelongsToServer(dm.serverId, resolveServer?.invoke(dm.id), targetServerId)) continue
        dmById[dm.id] = dm
        eligibleDms.add(dm)
    }

    val channelById = LinkedHashMap<String, VoiceDictationLocalChannelHint>()
    val eligibleChannels = ArrayList<VoiceDictationLocalChannelHint>()
    for (channel in snapshot.channels) {
        if (channel.archived || channel.isDmOrThread) continue
        if (!itemBelongsToServer(channel.serverId, resolveServer?.invoke(channel.id), targetServerId)) continue
        channelById[channel.id] = channel
        if (channel.joined) {
            eligibleChannels.add(channel)
        }
    }

    val candidates = ArrayList<String>()
    fun pushName(raw: String?) {
        if (!raw.isNullOrBlank()) {
            candidates.add(raw)
        }
    }
    fun pushChannelName(raw: String?) {
        if (raw == null) return
        val stripped = raw.trim().trimStart('#')
        if (stripped.isNotEmpty()) {
            candidates.add(stripped)
        }
    }

    // 1. Most recently visited / active conversations in this server scope.
    for (conversationId in snapshot.recentConversationIds) {
        val dm = dmById[conversationId]
        if (dm != null) {
            pushName(dm.peerDisplayName)
            pushName(dm.peerName)
            continue
        }
        val channel = channelById[conversationId]
        if (channel != null) {
            pushChannelName(channel.name)
        }
    }

    // 2. Active non-archived DMs sorted by most recent message activity descending.
    val sortedDms = eligibleDms.sortedByDescending { parseHintActivityEpochMs(it.lastActivityAt) }
    for (dm in sortedDms) {
        pushName(dm.peerDisplayName)
        pushName(dm.peerName)
    }

    // 3. Active non-deleted, non-service agents belonging to this server.
    for (agent in snapshot.agents) {
        if (agent.deleted || agent.serviceAgent) continue
        if (!itemBelongsToServer(agent.serverId, null, targetServerId)) continue
        pushName(agent.displayName)
        pushName(agent.name)
    }

    // 4. Joined non-archived channels sorted by most recent activity descending.
    val sortedChannels = eligibleChannels.sortedByDescending { parseHintActivityEpochMs(it.lastActivityAt) }
    for (channel in sortedChannels) {
        pushChannelName(channel.name)
    }

    // 5. Server members belonging to this server (skip current signed-in user).
    val currentUserId = snapshot.currentUserId?.trim()?.takeIf { it.isNotEmpty() }
    for (member in snapshot.members) {
        if (currentUserId != null && member.userId.trim() == currentUserId) continue
        if (!itemBelongsToServer(member.serverId, null, targetServerId)) continue
        pushName(member.displayName)
        pushName(member.name)
    }

    return normalizeVoiceDictationContactHints(candidates)
}

private fun itemBelongsToServer(
    itemServerId: String?,
    memoServerId: String?,
    targetServerId: String,
): Boolean {
    val cleanItem = itemServerId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanItem != null && cleanItem != targetServerId) return false
    val cleanMemo = memoServerId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanMemo != null && cleanMemo != targetServerId) return false
    return true
}

private fun parseHintActivityEpochMs(iso: String?): Long {
    val trimmed = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
    return runCatching { Instant.parse(trimmed).toEpochMilliseconds() }.getOrNull() ?: 0L
}

private fun normalizeSingleContactHint(raw: String): String {
    // Pass 1: collapse whitespace/newlines (including \r, \n, \t) to a single ASCII space BEFORE
    // stripping Cc/Cf so inputs like "Bob\u0007\r\nLee" keep a word separator ("Bob Lee"),
    // matching `slock`'s `.replace(/\s+/g, " ").replace(/[\p{Cc}\p{Cf}]/gu, "").replace(/\s+/g, " ").trim()`.
    val whitespaceCollapsed =
        buildString(raw.length) {
            var inWhitespace = false
            var index = 0
            while (index < raw.length) {
                val ch = raw[index]
                if (ch.isHighSurrogate() && index + 1 < raw.length && raw[index + 1].isLowSurrogate()) {
                    inWhitespace = false
                    append(ch)
                    append(raw[index + 1])
                    index += 2
                } else if (ch.isContactHintWhitespace()) {
                    if (!inWhitespace) {
                        append(' ')
                        inWhitespace = true
                    }
                    index += 1
                } else {
                    inWhitespace = false
                    append(ch)
                    index += 1
                }
            }
        }
    // Pass 2: strip Unicode Cc/Cf code points, collapse any newly adjacent spaces, and trim.
    val stripped = stripControlAndFormatCodePoints(whitespaceCollapsed, preserveNewline = false)
    val collapsed =
        buildString(stripped.length) {
            var pendingSpace = false
            var hasContent = false
            var index = 0
            while (index < stripped.length) {
                val ch = stripped[index]
                if (ch.isHighSurrogate() && index + 1 < stripped.length && stripped[index + 1].isLowSurrogate()) {
                    if (pendingSpace && hasContent) append(' ')
                    pendingSpace = false
                    hasContent = true
                    append(ch)
                    append(stripped[index + 1])
                    index += 2
                } else if (ch.isContactHintWhitespace()) {
                    if (hasContent) pendingSpace = true
                    index += 1
                } else {
                    if (pendingSpace && hasContent) append(' ')
                    pendingSpace = false
                    hasContent = true
                    append(ch)
                    index += 1
                }
            }
        }
    return collapsed
}

private fun stripControlAndFormatCodePoints(
    raw: String,
    preserveNewline: Boolean,
    preserveTab: Boolean = false,
): String =
    buildString(raw.length) {
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch.isHighSurrogate() && index + 1 < raw.length && raw[index + 1].isLowSurrogate()) {
                val low = raw[index + 1]
                val codePoint = 0x10000 + ((ch.code - 0xD800) shl 10) + (low.code - 0xDC00)
                if (!isAstralFormatCodePoint(codePoint)) {
                    append(ch)
                    append(low)
                }
                index += 2
            } else {
                val keep =
                    (preserveNewline && ch == '\n') ||
                        (preserveTab && ch == '\t') ||
                        (ch.category != CharCategory.CONTROL && ch.category != CharCategory.FORMAT)
                if (keep) {
                    append(ch)
                }
                index += 1
            }
        }
    }

private fun countUnicodeCodePoints(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        count += 1
        index +=
            if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
    }
    return count
}

private fun isAstralFormatCodePoint(codePoint: Int): Boolean =
    codePoint == 0x110BD ||
        codePoint == 0x110CD ||
        codePoint in 0x13430..0x1343F ||
        codePoint in 0x1BCA0..0x1BCA3 ||
        codePoint in 0x1D173..0x1D17A ||
        codePoint == 0xE0001 ||
        codePoint in 0xE0020..0xE007F

private fun Char.isContactHintWhitespace(): Boolean =
    this == ' ' ||
        this == '\t' ||
        this == '\n' ||
        this == '\u000B' ||
        this == '\u000C' ||
        this == '\r' ||
        this == '\u00A0' ||
        this == '\u1680' ||
        this in '\u2000'..'\u200A' ||
        this == '\u2028' ||
        this == '\u2029' ||
        this == '\u202F' ||
        this == '\u205F' ||
        this == '\u3000'
