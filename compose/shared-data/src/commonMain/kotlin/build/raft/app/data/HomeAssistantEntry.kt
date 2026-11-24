package build.raft.app.data

/*
 * Whether the phone Home shows its Assistant row (web botiverse/slock
 * `packages/web/src/components/layout/useRememberedSidebarEntry.ts` `useRememberedAssistantShown`
 * and `sidebarSectionRowCounts.ts` `sidebarEntryShown` / `readSidebarEntryShown` /
 * `writeSidebarEntryShown`, #14598):
 *
 * - The answer: shown once "my assistant" is ready (not a guest, the `personal_assistant` flag on,
 *   and `GET servers/:id/assistant` says `enabled`), with or without an assistant yet (the page
 *   then offers Create); hidden once it is off. Null while that is not known.
 * - Until then: whatever was shown the last time the answer was known for this person on this
 *   server, so the row is there from the start instead of pushing the list down when it lands.
 * - Every known answer is remembered, except one taken while the flag could not be read (web
 *   `undetermined`): that reads as off and hides the row, but says nothing about next time.
 */

/** The answer of [state]: true ready, false off, null still loading. */
fun homeAssistantEntryAnswer(state: MyAssistantState): Boolean? =
    when (state) {
        MyAssistantState.Loading -> null
        MyAssistantState.Off -> false
        is MyAssistantState.Ready -> true
    }

/** Web `sidebarEntryShown`: the answer once known, else what was remembered, else hidden. */
fun homeAssistantEntryShown(answer: Boolean?, remembered: Boolean?): Boolean = answer ?: remembered ?: false

/** Whether [answer] is written back: a known answer, unless the flag could not be read. */
fun homeAssistantEntryRemembers(answer: Boolean?, flagUndetermined: Boolean): Boolean = answer != null && !flagUndetermined

/**
 * The remembered answer per person and server, on the device (web `localStorage`
 * `slock:sidebarEntry:<user>:<server>:assistant`; here under the app's own `raft:` prefix).
 */
class HomeAssistantEntryMemory(private val store: SharedPreferenceStore) {
    /** What was shown last time; null when never known here (or unreadable). */
    fun read(userId: String?, serverId: String?): Boolean? {
        val key = key(userId, serverId) ?: return null
        return when (runCatching { store.getString(key) }.getOrNull()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun write(userId: String?, serverId: String?, shown: Boolean) {
        val key = key(userId, serverId) ?: return
        val value = if (shown) "1" else "0"
        // Storage full or blocked: the row just isn't remembered.
        runCatching { if (store.getString(key) != value) store.putString(key, value) }
    }

    companion object {
        const val KEY_PREFIX = "raft:sidebarEntry"

        fun key(userId: String?, serverId: String?): String? {
            val user = userId?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val server = serverId?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return "$KEY_PREFIX:$user:$server:assistant"
        }
    }
}
