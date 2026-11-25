package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId

/**
 * Actions eligible for the single exposed primary button beside `⋯` on a phone conversation header
 * (`mobile_phone_header_action_v0`, `#wg-mobile-sync:#13`).
 */
internal enum class PhoneConversationHeaderActionSlot(val actionId: String) {
    Search("thread-search"),
    Tasks("thread-side-card-tasks"),
    Files("thread-side-card-files"),
    Details("thread-settings"),
    Call("thread-call"),
}

/** One entry in the ordered priority list parsed from `mobile_phone_header_action_v0`'s `variant`. */
internal sealed interface PhoneConversationHeaderPrimaryEntry {
    data class Slot(val slot: PhoneConversationHeaderActionSlot) : PhoneConversationHeaderPrimaryEntry

    /** Explicitly expose no standalone action beside `⋯` (all available actions fold into `⋯`). */
    data object None : PhoneConversationHeaderPrimaryEntry
}

/**
 * Server-driven configuration for which action (if any) sits beside `⋯` on a phone conversation
 * header (`mobile_phone_header_action_v0`, `#wg-mobile-sync:#13`).
 *
 * When [entries] is empty (flag off, missing, blank, or only unrecognized tokens), the default
 * `#16464` behavior applies (`Search` primary).
 */
internal data class PhoneConversationHeaderPrimaryConfig(
    val entries: List<PhoneConversationHeaderPrimaryEntry> = emptyList(),
) {
    val isDefault: Boolean get() = entries.isEmpty()

    companion object {
        val Default: PhoneConversationHeaderPrimaryConfig = PhoneConversationHeaderPrimaryConfig()
    }
}

/**
 * Parses a comma-separated `mobile_phone_header_action_v0` variant string (case-insensitive, trimmed):
 * `search`, `tasks`, `files`, `details` (alias `settings`), `call`, `none`. Unknown tokens are
 * ignored; blank or all-unknown strings return [PhoneConversationHeaderPrimaryConfig.Default].
 */
internal fun parsePhoneConversationHeaderPrimaryConfig(
    variant: String?,
): PhoneConversationHeaderPrimaryConfig {
    val raw = variant?.trim()?.takeIf { it.isNotEmpty() } ?: return PhoneConversationHeaderPrimaryConfig.Default
    val parsed =
        buildList {
            val seen = mutableSetOf<PhoneConversationHeaderPrimaryEntry>()
            for (token in raw.split(',')) {
                val entry =
                    when (token.trim().lowercase()) {
                        "search" -> PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Search)
                        "tasks" -> PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Tasks)
                        "files" -> PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Files)
                        "details", "settings" -> PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Details)
                        "call" -> PhoneConversationHeaderPrimaryEntry.Slot(PhoneConversationHeaderActionSlot.Call)
                        "none" -> PhoneConversationHeaderPrimaryEntry.None
                        else -> null
                    } ?: continue
                if (seen.add(entry)) add(entry)
                if (entry == PhoneConversationHeaderPrimaryEntry.None) break
            }
        }
    return if (parsed.isEmpty()) PhoneConversationHeaderPrimaryConfig.Default else PhoneConversationHeaderPrimaryConfig(parsed)
}

internal fun ServerFeatureFlagSnapshot.phoneConversationHeaderPrimaryConfig(): PhoneConversationHeaderPrimaryConfig =
    if (isEnabled(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY)) {
        parsePhoneConversationHeaderPrimaryConfig(variant(MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY))
    } else {
        PhoneConversationHeaderPrimaryConfig.Default
    }

internal data class PhoneConversationHeaderActionSplit<T>(
    val visible: List<T>,
    val folded: List<T>,
)

private const val PhoneHeaderPrimaryNoneIndex: Int = -1

/**
 * Splits a phone top-level conversation header's own actions into the exposed primary button(s)
 * and the actions folded into `⋯`, preserving canonical head order in both lists:
 *
 * - When [config] reaches `none` before any available slot, all actions fold into `⋯` so `⋯` is
 *   the sole trailing button (when at least one action exists).
 * - Otherwise, if there are no pre-existing menu items and `actions.size <= ThreadHeaderPhoneMaxTrailingButtons`,
 *   all actions remain visible without creating a 1-item `⋯` menu (`#16464`).
 * - Otherwise, walks [config.entries] in order and exposes the first slot present in [actions].
 *   If [config] is default or none of its slots are available on this conversation, falls back to
 *   [PhoneConversationHeaderActionSlot.Search] if available, else the first available action.
 * - Every remaining action goes into [PhoneConversationHeaderActionSplit.folded] in canonical head order.
 */
internal fun <T> splitPhoneConversationHeaderActions(
    actions: List<T>,
    idOf: (T) -> String,
    existingMenuItems: Boolean = false,
    config: PhoneConversationHeaderPrimaryConfig = PhoneConversationHeaderPrimaryConfig.Default,
): PhoneConversationHeaderActionSplit<T> {
    if (actions.isEmpty()) return PhoneConversationHeaderActionSplit(emptyList(), emptyList())

    val matchedIndex = resolvePhoneConversationHeaderPrimaryIndex(actions, idOf, config)
    if (matchedIndex == PhoneHeaderPrimaryNoneIndex) {
        return PhoneConversationHeaderActionSplit(visible = emptyList(), folded = actions)
    }

    if (!existingMenuItems && actions.size <= ThreadHeaderPhoneMaxTrailingButtons) {
        return PhoneConversationHeaderActionSplit(visible = actions, folded = emptyList())
    }

    val primaryIndex =
        matchedIndex
            ?: actions.indexOfFirst { idOf(it) == PhoneConversationHeaderActionSlot.Search.actionId }.takeIf { it >= 0 }
            ?: 0
    return PhoneConversationHeaderActionSplit(
        visible = listOf(actions[primaryIndex]),
        folded = actions.filterIndexed { index, _ -> index != primaryIndex },
    )
}

private fun <T> resolvePhoneConversationHeaderPrimaryIndex(
    actions: List<T>,
    idOf: (T) -> String,
    config: PhoneConversationHeaderPrimaryConfig,
): Int? {
    for (entry in config.entries) {
        when (entry) {
            PhoneConversationHeaderPrimaryEntry.None -> return PhoneHeaderPrimaryNoneIndex
            is PhoneConversationHeaderPrimaryEntry.Slot -> {
                val index = actions.indexOfFirst { idOf(it) == entry.slot.actionId }
                if (index >= 0) return index
            }
        }
    }
    return null
}

/**
 * Propagates toggle/active state (`Tasks`, `Files`, `Details`) onto iOS native navigation bar
 * actions so a promoted trailing toggle or folded menu row reflects when its surface is open.
 */
internal fun applyThreadHeaderNativeNavBarCheckedState(
    actions: List<RaftNativeNavBarAction>,
    sideCardToggles: ThreadHeaderSideCardToggles?,
    settingsOpen: Boolean,
): List<RaftNativeNavBarAction> =
    actions.map { action ->
        val checked =
            when (action.id) {
                "thread-side-card-tasks" -> sideCardToggles?.cards?.isOpen(ConversationSideCard.Tasks) == true
                "thread-side-card-files" -> sideCardToggles?.cards?.isOpen(ConversationSideCard.Files) == true
                "thread-settings" -> settingsOpen
                else -> action.checked
            }
        if (checked == action.checked) action else action.copy(checked = checked)
    }

/**
 * Active server's phone conversation header primary action config (`mobile_phone_header_action_v0`),
 * provided by `ThreadPage` and defaulted to [PhoneConversationHeaderPrimaryConfig.Default] elsewhere.
 */
internal val LocalPhoneConversationHeaderPrimaryConfig =
    staticCompositionLocalOf { PhoneConversationHeaderPrimaryConfig.Default }

@Composable
internal fun rememberPhoneConversationHeaderPrimaryConfig(
    flags: SharedServerFeatureFlags?,
    serverId: ServerId?,
): PhoneConversationHeaderPrimaryConfig {
    val revision = flags?.snapshotRevision?.collectAsState()?.value
    return remember(flags, serverId?.value, revision) {
        flags?.snapshotFor(serverId)?.phoneConversationHeaderPrimaryConfig()
            ?: PhoneConversationHeaderPrimaryConfig.Default
    }
}
