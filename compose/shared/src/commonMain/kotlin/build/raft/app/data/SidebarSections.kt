package build.raft.app.data

import kotlin.random.Random

/**
 * task #512: pure helpers for sidebar custom sections, ported from web
 * `packages/web/src/store/sidebarSections.ts`. These operate on the sidebar-order
 * snapshot models so the parser, the store, and the UI share one normalization
 * and placement semantics.
 */

/** System section ids, in web's canonical order. */
val SIDEBAR_SYSTEM_SECTION_IDS: List<String> =
    listOf("system:pinned", "system:joint", "system:channels", "system:dms")

const val SIDEBAR_SECTION_ITEM_KIND_CHANNEL = "channel"
const val SIDEBAR_SECTION_ITEM_KIND_AGENT = "agent"

val SIDEBAR_SECTION_SORT_MODES: Set<String> = setOf("manual", "recent", "az")

/**
 * Keep only well-formed custom sections (non-blank id/name, known sort mode).
 * Mirrors web `normalizeSidebarCustomSections`.
 */
fun normalizeSidebarCustomSections(
    sections: List<SettingsServerSidebarCustomSection>
): List<SettingsServerSidebarCustomSection> =
    sections.filter { section ->
        section.id.isNotBlank() &&
            section.name.isNotBlank() &&
            section.sortMode in SIDEBAR_SECTION_SORT_MODES
    }

/**
 * Keep only well-formed placements (known kind, non-blank ids, sane position).
 * Mirrors web `normalizeSidebarSectionPlacements`.
 */
fun normalizeSidebarSectionPlacements(
    placements: List<SettingsServerSidebarSectionPlacement>
): List<SettingsServerSidebarSectionPlacement> =
    placements.filter { placement ->
        (placement.kind == SIDEBAR_SECTION_ITEM_KIND_CHANNEL ||
            placement.kind == SIDEBAR_SECTION_ITEM_KIND_AGENT) &&
            placement.id.isNotBlank() &&
            placement.sectionId.isNotBlank() &&
            placement.position >= 0
    }

/**
 * Dedupe [order] to known section ids (system + custom), then append any
 * section missing from the input: system sections first, then custom sections
 * in their declaration order. Mirrors web `normalizeSidebarSectionOrder`.
 */
fun normalizeSidebarSectionOrder(
    order: List<String>,
    sections: List<SettingsServerSidebarCustomSection>
): List<String> {
    val customIds = sections.map { it.id }
    val allowedIds = (SIDEBAR_SYSTEM_SECTION_IDS + customIds).toSet()
    val orderedIds = ArrayList<String>(allowedIds.size)
    val seen = HashSet<String>(allowedIds.size)
    for (id in order + SIDEBAR_SYSTEM_SECTION_IDS + customIds) {
        if (id !in allowedIds || !seen.add(id)) continue
        orderedIds.add(id)
    }
    return orderedIds
}

/** Move [activeId] to [overId]'s index. Mirrors web `reorderSidebarSectionOrder`. */
fun reorderSidebarSectionOrder(order: List<String>, activeId: String, overId: String): List<String> {
    val oldIndex = order.indexOf(activeId)
    val newIndex = order.indexOf(overId)
    if (oldIndex == -1 || newIndex == -1 || oldIndex == newIndex) return order
    val next = order.toMutableList()
    val active = next.removeAt(oldIndex)
    next.add(newIndex, active)
    return next
}

fun sidebarSectionItemKey(kind: String, id: String): String = "$kind:$id"

/**
 * The placement key of a sidebar conversation. Web keys agent-peer DMs by
 * their agent member id (kind "agent") and everything else — channels, joint
 * channels, human DMs — by channel id (kind "channel"); see Sidebar.tsx
 * `resolveMovableItemFromDragId` / `contextMovableItem`. Store writes and UI
 * resolution must derive the same key for the same conversation.
 */
fun sidebarSectionPlacementKindId(
    peerType: String?,
    presenceMemberId: String?,
    conversationId: String
): Pair<String, String> =
    if (peerType.equals(SIDEBAR_SECTION_ITEM_KIND_AGENT, ignoreCase = true) && !presenceMemberId.isNullOrBlank()) {
        SIDEBAR_SECTION_ITEM_KIND_AGENT to presenceMemberId
    } else {
        SIDEBAR_SECTION_ITEM_KIND_CHANNEL to conversationId
    }

/**
 * New custom-section id. Web uses `crypto.randomUUID()`; commonMain has no
 * java.util.UUID, so this produces the same version-4 UUID shape (the server
 * accepts any non-"system:" id up to 80 chars).
 */
fun newSidebarSectionId(randomLong: () -> Long = { Random.nextLong() }): String {
    val high = randomLong().toULong()
    val low = randomLong().toULong()
    val timeLow = (high shr 32).toString(16).padStart(8, '0')
    val timeMid = (high shr 16 and 0xffffuL).toString(16).padStart(4, '0')
    val timeHigh = ((high and 0x0fffuL) or 0x4000uL).toString(16).padStart(4, '0')
    val clock = ((low shr 48 and 0x3fffuL) or 0x8000uL).toString(16).padStart(4, '0')
    val node = (low and 0xffffffffffffuL).toString(16).padStart(12, '0')
    return "$timeLow-$timeMid-$timeHigh-$clock-$node"
}

/** Append the item to the end of [sectionId]. Mirrors web `moveSidebarItemToCustomSection`. */
fun moveSidebarItemToCustomSection(
    placements: List<SettingsServerSidebarSectionPlacement>,
    kind: String,
    id: String,
    sectionId: String
): List<SettingsServerSidebarSectionPlacement> =
    moveSidebarItemToCustomSectionAtPosition(placements, kind, id, sectionId, Int.MAX_VALUE)

/**
 * Move the item into [sectionId] at [requestedPosition] (clamped), reindexing
 * the destination section's positions to 0..n-1. Mirrors web
 * `moveSidebarItemToCustomSectionAtPosition`.
 */
fun moveSidebarItemToCustomSectionAtPosition(
    placements: List<SettingsServerSidebarSectionPlacement>,
    kind: String,
    id: String,
    sectionId: String,
    requestedPosition: Int
): List<SettingsServerSidebarSectionPlacement> {
    val itemKey = sidebarSectionItemKey(kind, id)
    val withoutItem = placements.filter { sidebarSectionItemKey(it.kind, it.id) != itemKey }
    val destination =
        withoutItem
            .filter { it.sectionId == sectionId }
            .sortedBy { it.position }
            .toMutableList()
    val position = requestedPosition.coerceIn(0, destination.size)
    destination.add(position, SettingsServerSidebarSectionPlacement(kind = kind, id = id, sectionId = sectionId, position = position))
    val reindexedDestination = destination.mapIndexed { index, placement -> placement.copy(position = index) }
    return withoutItem.filter { it.sectionId != sectionId } + reindexedDestination
}

/** Remove the item's placement, if any. Mirrors web `removeSidebarItemPlacement`. */
fun removeSidebarItemPlacement(
    placements: List<SettingsServerSidebarSectionPlacement>,
    kind: String,
    id: String
): List<SettingsServerSidebarSectionPlacement> {
    val itemKey = sidebarSectionItemKey(kind, id)
    if (placements.none { sidebarSectionItemKey(it.kind, it.id) == itemKey }) return placements
    return placements.filter { sidebarSectionItemKey(it.kind, it.id) != itemKey }
}

/**
 * The section order after adding new custom section [sectionId]: inserted
 * before [insertBeforeSectionId] when that id is in the (normalized) order,
 * appended otherwise. Mirrors web Sidebar.tsx `createCustomSection`.
 */
fun insertSidebarSectionIntoOrder(
    order: List<String>,
    customSections: List<SettingsServerSidebarCustomSection>,
    sectionId: String,
    insertBeforeSectionId: String?
): List<String> {
    val normalized = normalizeSidebarSectionOrder(order, customSections).filter { it != sectionId }
    val insertAt = insertBeforeSectionId?.let { normalized.indexOf(it) } ?: -1
    return if (insertAt >= 0) {
        normalized.subList(0, insertAt) + sectionId + normalized.subList(insertAt, normalized.size)
    } else {
        normalized + sectionId
    }
}
