package build.raft.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.designsystem.components.RaftPaneGridArrangement
import build.raft.designsystem.components.RaftPaneGridArrangementColumn
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * The layout of the sections right of the conversation (#189 step 4), a port of the web's
 * packages/shared/src/channelSideLayout.ts (slock origin/staging 5fa46117cd): Thread, Tasks, Files, the Artifact, the
 * profile, the context view and the channel details, in one or more columns right of the conversation, each column a
 * vertical stack. The web keeps one per person per server on the server (`channel_side_layout`, through PATCH
 * /servers/:id/sidebar-order); the app keeps it per device for now ([RaftChannelSideLayoutStore]), in the web's shape,
 * so the server sync can come later without a migration.
 *
 * Only the shape is checked: a stored value that is not one (an unknown section, too many columns, a width out of
 * range) reads as "never saved", and the default applies. Fitting the columns into a window (minimum widths, clipping,
 * tabs) is the row plan's, at render time; it never rewrites what is saved.
 */

/** Web `CHANNEL_SIDE_SECTIONS`, in its order; [wire] is the web's name, also the pane grid cell id the section is drawn in. */
internal enum class RaftChannelSideSection(val wire: String) {
    Thread("thread"),
    Tasks("tasks"),
    Files("files"),
    Artifact("artifact"),
    Profile("profile"),
    Context("context"),
    Details("details"),
    ;

    companion object {
        fun ofWire(wire: String): RaftChannelSideSection? = entries.firstOrNull { it.wire == wire }
    }
}

/** Web `ChannelSideLayoutColumn`: its sections top to bottom (every section is in exactly one column), its width in dp. */
@Immutable
internal data class RaftChannelSideColumn(val sections: List<RaftChannelSideSection>, val width: Int)

/** Web `ChannelSideLayout` (`v` 1): its columns left to right, right of the conversation. Compared by value (web `sameLayout`). */
@Immutable
internal data class RaftChannelSideLayout(val columns: List<RaftChannelSideColumn>)

/** Web `CHANNEL_SIDE_MAX_COLUMNS`: at most four side columns, besides a column of the thread's own. */
internal const val RAFT_CHANNEL_SIDE_MAX_COLUMNS = 4

/** Web `CHANNEL_SIDE_MIN_SAVED_WIDTH` / `CHANNEL_SIDE_MAX_SAVED_WIDTH`: a column may be dragged as narrow as 160. */
internal const val RAFT_CHANNEL_SIDE_MIN_SAVED_WIDTH = 160
internal const val RAFT_CHANNEL_SIDE_MAX_SAVED_WIDTH = 4000

/** Web `CHANNEL_SIDE_SECTION_MIN_WIDTH`: a section's narrowest readable width; a column's is its widest section's. */
internal fun RaftChannelSideSection.minWidth(): Int =
    when (this) {
        RaftChannelSideSection.Thread -> 360
        RaftChannelSideSection.Tasks, RaftChannelSideSection.Files -> 280
        RaftChannelSideSection.Artifact -> 480
        RaftChannelSideSection.Profile, RaftChannelSideSection.Context, RaftChannelSideSection.Details -> 320
    }

/**
 * Web `PROFILE_FOLLOWERS`: the context behind an agent's message (View Context) and the channel details follow the
 * profile: placed under it, carried with it, never left alone in a column, so a closed one never takes one of the four.
 */
private val PROFILE_FOLLOWERS = listOf(RaftChannelSideSection.Context, RaftChannelSideSection.Details)

private fun followsProfile(section: RaftChannelSideSection): Boolean = section in PROFILE_FOLLOWERS

/**
 * Web `DEFAULT_CHANNEL_SIDE_LAYOUT`: the thread in a column of its own, Tasks over Files right of it, then the
 * Artifact, then the profile with the context view and the channel details under it.
 */
internal val RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT =
    RaftChannelSideLayout(
        listOf(
            RaftChannelSideColumn(listOf(RaftChannelSideSection.Thread), 400),
            RaftChannelSideColumn(listOf(RaftChannelSideSection.Tasks, RaftChannelSideSection.Files), 360),
            RaftChannelSideColumn(listOf(RaftChannelSideSection.Artifact), 480),
            RaftChannelSideColumn(listOf(RaftChannelSideSection.Profile, RaftChannelSideSection.Context, RaftChannelSideSection.Details), 400),
        )
    )

/** Web `channelSideSectionWidth`: a section's column of its own where it has none to keep: the default layout's. */
internal fun raftChannelSideSectionWidth(section: RaftChannelSideSection): Int =
    RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT.columns.firstOrNull { section in it.sections }?.width ?: 360

/** The thread's column of its own doesn't count among the side columns. */
private fun sideColumnCount(columns: List<RaftChannelSideColumn>): Int =
    columns.count { !(it.sections.size == 1 && it.sections[0] == RaftChannelSideSection.Thread) }

/**
 * Web `parseChannelSideLayout`: the layout [raw] is, or null when it is not one: `v` 1; 1 to 4 side columns (and the
 * thread's own); each with at least one section, no section twice in the whole layout; an integer width from 160 to
 * 4000. A section this build doesn't know (a newer client's, or a retired one) is dropped, and a column left with none
 * goes too. Sections missing from it are allowed: [raftCompleteChannelSideLayout] places them.
 */
internal fun raftParseChannelSideLayout(raw: JsonElement?): RaftChannelSideLayout? {
    val value = raw as? JsonObject ?: return null
    val v = (value["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    val columns = value["columns"] as? JsonArray
    if (v != 1.0 || columns == null || columns.isEmpty() || columns.size > RAFT_CHANNEL_SIDE_MAX_COLUMNS + 1) return null
    val seen = mutableSetOf<RaftChannelSideSection>()
    val parsed = mutableListOf<RaftChannelSideColumn>()
    for (column in columns) {
        val obj = column as? JsonObject ?: return null
        val sections = obj["sections"] as? JsonArray
        if (sections == null || sections.isEmpty()) return null
        val width = (obj["width"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if (!width.isFinite() || floor(width) != width) return null
        if (width < RAFT_CHANNEL_SIDE_MIN_SAVED_WIDTH || width > RAFT_CHANNEL_SIDE_MAX_SAVED_WIDTH) return null
        val own = mutableListOf<RaftChannelSideSection>()
        for (element in sections) {
            // A section this build doesn't know (a newer client's): dropped, the rest of the layout kept.
            val section = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(RaftChannelSideSection::ofWire) ?: continue
            if (!seen.add(section)) return null
            own += section
        }
        // A column of only unknown sections goes with them.
        if (own.isNotEmpty()) parsed += RaftChannelSideColumn(own, width.toInt())
    }
    if (parsed.isEmpty() || sideColumnCount(parsed) > RAFT_CHANNEL_SIDE_MAX_COLUMNS) return null
    return RaftChannelSideLayout(parsed)
}

/** [raftParseChannelSideLayout] of stored text; null where it is none or not JSON. */
internal fun raftParseChannelSideLayoutText(text: String?): RaftChannelSideLayout? =
    text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.let(::raftParseChannelSideLayout)

/** The web's shape: `{"v":1,"columns":[{"sections":["thread"],"width":400}, …]}`. */
internal fun RaftChannelSideLayout.toJson(): JsonObject =
    buildJsonObject {
        put("v", 1)
        put(
            "columns",
            buildJsonArray {
                columns.forEach { column ->
                    add(
                        buildJsonObject {
                            put("sections", buildJsonArray { column.sections.forEach { add(JsonPrimitive(it.wire)) } })
                            put("width", column.width)
                        }
                    )
                }
            },
        )
    }

/** A column being edited: its sections and width. */
private class Editing(val sections: MutableList<RaftChannelSideSection>, val width: Int) {
    fun frozen() = RaftChannelSideColumn(sections.toList(), width)
}

private fun RaftChannelSideColumn.editing() = Editing(sections.toMutableList(), width)

/**
 * Web `completeChannelSideLayout`: every section placed exactly once. A section the layout lacks goes where the
 * default puts it, after the sections it follows there (the thread into a new first column; Tasks or Files under the
 * other one, or into a new last column; the Artifact and the profile into a new last column, or at the bottom of the
 * last one where there is none to make; the context view and the channel details under the profile, else the same
 * way). A column holding only those is not kept: it goes under the profile.
 */
internal fun raftCompleteChannelSideLayout(layout: RaftChannelSideLayout): RaftChannelSideLayout {
    val columns = layout.columns.map { it.editing() }.toMutableList()
    val placed = columns.flatMap { it.sections }.toMutableSet()
    fun count() = sideColumnCount(columns.map { it.frozen() })
    for (section in RaftChannelSideSection.entries) {
        if (section in placed) continue
        val profileColumn = if (followsProfile(section)) columns.firstOrNull { RaftChannelSideSection.Profile in it.sections } else null
        if (section == RaftChannelSideSection.Thread) {
            columns.add(0, Editing(mutableListOf(RaftChannelSideSection.Thread), RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT.columns[0].width))
        } else if (profileColumn != null) {
            profileColumn.sections += section
        } else if (section == RaftChannelSideSection.Artifact || section == RaftChannelSideSection.Profile || followsProfile(section)) {
            if (count() < RAFT_CHANNEL_SIDE_MAX_COLUMNS) columns += Editing(mutableListOf(section), raftChannelSideSectionWidth(section))
            else columns.last().sections += section
        } else {
            val sibling =
                columns.lastOrNull { column -> column.sections.any { it == RaftChannelSideSection.Tasks || it == RaftChannelSideSection.Files } }
            if (sibling != null) sibling.sections += section
            else if (count() < RAFT_CHANNEL_SIDE_MAX_COLUMNS) columns += Editing(mutableListOf(section), RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT.columns[1].width)
            else columns.last().sections += section
        }
        placed += section
    }
    return foldFollowerOnlyColumns(RaftChannelSideLayout(columns.map { it.frozen() }))
}

/** Web `channelSideColumnMinWidth`: a column's narrowest readable width, its widest section's minimum. */
internal fun raftChannelSideColumnMinWidth(sections: List<RaftChannelSideSection>): Int = sections.maxOfOrNull { it.minWidth() }?.coerceAtLeast(0) ?: 0

/** Web `VisibleChannelSideColumn`: a column with an open section ([column]: its index in the layout), its open sections. */
@Immutable
internal data class RaftVisibleChannelSideColumn(val column: Int, val sections: List<RaftChannelSideSection>, val width: Int, val minWidth: Int)

/** Web `visibleChannelSideColumns`: the columns with an open section, left to right; a column with none shows nothing. */
internal fun raftVisibleChannelSideColumns(layout: RaftChannelSideLayout, open: Set<RaftChannelSideSection>): List<RaftVisibleChannelSideColumn> =
    layout.columns.mapIndexedNotNull { index, column ->
        val sections = column.sections.filter { it in open }
        if (sections.isEmpty()) null else RaftVisibleChannelSideColumn(index, sections, column.width, raftChannelSideColumnMinWidth(sections))
    }

/**
 * Web `ChannelSideDrop`: where a moved section lands: into column [Into.column] before its section [Into.before] (null:
 * at the bottom), or as a new column before column [NewColumn.at] (the layout's index; its size for a new last one).
 */
internal sealed interface RaftChannelSideDrop {
    data class Into(val column: Int, val before: RaftChannelSideSection?) : RaftChannelSideDrop

    data class NewColumn(val at: Int) : RaftChannelSideDrop
}

/**
 * Web `moveChannelSideSection`: the layout with [section] moved to [drop]. A column left with no section disappears.
 * A section moved out alone keeps its column's width; out of a stack it takes its own default. Returns [layout] itself
 * when the drop is where the section already is, or would make a fifth side column. The profile carries the context
 * view and the details sitting right under it, and no column is left holding only those.
 */
internal fun raftMoveChannelSideSection(
    layout: RaftChannelSideLayout,
    section: RaftChannelSideSection,
    drop: RaftChannelSideDrop,
): RaftChannelSideLayout {
    val from = layout.columns.indexOfFirst { section in it.sections }
    if (from < 0) return layout
    val moving = movingSections(layout.columns[from].sections, section)
    fun without(column: RaftChannelSideColumn) = Editing(column.sections.filter { it !in moving }.toMutableList(), column.width)
    when (drop) {
        is RaftChannelSideDrop.Into -> {
            if (drop.column < 0 || drop.column >= layout.columns.size || (drop.before != null && drop.before in moving)) return layout
            val target = layout.columns[drop.column]
            if (drop.before != null && drop.before !in target.sections) return layout
            val columns = layout.columns.map(::without)
            val into = columns[drop.column]
            val at = if (drop.before == null) into.sections.size else into.sections.indexOf(drop.before)
            into.sections.addAll(at, moving)
            val next = foldFollowerOnlyColumns(RaftChannelSideLayout(columns.filter { it.sections.isNotEmpty() }.map { it.frozen() }))
            return if (next == layout) layout else next
        }
        is RaftChannelSideDrop.NewColumn -> {
            if (drop.at < 0 || drop.at > layout.columns.size) return layout
            val alone = layout.columns[from].sections.all { it in moving }
            // Dropping a section that is alone in its column at either of its own edges changes nothing.
            if (alone && (drop.at == from || drop.at == from + 1)) return layout
            // Its saved width goes with it: alone, its column's; out of a stack, its own default.
            val width = if (alone) layout.columns[from].width else raftChannelSideSectionWidth(section)
            val columns = layout.columns.map(::without).toMutableList()
            columns.add(drop.at, Editing(moving.toMutableList(), width))
            val next = foldFollowerOnlyColumns(RaftChannelSideLayout(columns.filter { it.sections.isNotEmpty() }.map { it.frozen() }))
            if (next == layout) return layout
            return if (sideColumnCount(next.columns) > RAFT_CHANNEL_SIDE_MAX_COLUMNS) layout else next
        }
    }
}

/** Web `movingSections`: the profile carries the followers sitting right under it. */
private fun movingSections(sections: List<RaftChannelSideSection>, section: RaftChannelSideSection): List<RaftChannelSideSection> {
    if (section != RaftChannelSideSection.Profile) return listOf(section)
    val moving = mutableListOf(RaftChannelSideSection.Profile)
    var at = sections.indexOf(RaftChannelSideSection.Profile) + 1
    while (at < sections.size && followsProfile(sections[at])) {
        moving += sections[at]
        at += 1
    }
    return moving
}

/** Web `foldFollowerOnlyColumns`: a column holding only the profile's followers goes under the profile, in the default's order. */
private fun foldFollowerOnlyColumns(layout: RaftChannelSideLayout): RaftChannelSideLayout {
    val withProfile = layout.columns.indexOfFirst { RaftChannelSideSection.Profile in it.sections }
    val lone = layout.columns.indices.filter { i -> layout.columns[i].sections.all(::followsProfile) }
    if (lone.isEmpty() || withProfile < 0) return layout
    val columns = layout.columns.indices.filter { it !in lone }.map { layout.columns[it].editing() }
    val profile = columns.firstOrNull { RaftChannelSideSection.Profile in it.sections }?.sections ?: return layout
    val start = profile.indexOf(RaftChannelSideSection.Profile) + 1
    var end = start
    while (end < profile.size && followsProfile(profile[end])) end += 1
    // The run under the profile, in the default's order.
    val run = (profile.subList(start, end).toList() + lone.flatMap { layout.columns[it].sections }).sortedBy { PROFILE_FOLLOWERS.indexOf(it) }
    repeat(end - start) { profile.removeAt(start) }
    profile.addAll(start, run)
    return RaftChannelSideLayout(columns.map { it.frozen() })
}

/** Where ⋯ > Move Left / Move Right put a section ([raftColumnMoves]); null where it can't go that way. */
@Immutable
internal data class RaftColumnMoves(val left: RaftChannelSideDrop?, val right: RaftChannelSideDrop?)

/**
 * Web columnMovePlaces `columnMoves` (#189 rule 7's keyboard path, the prototype's keyMoves): where Move Left and Move
 * Right put [section] among the columns showing [open]'s sections. Out of a column it shares, into a new column of its
 * own beside it; alone in its column, the column swaps places with its neighbour. Only where it changes what shows,
 * and never past four side columns.
 */
internal fun raftColumnMoves(layout: RaftChannelSideLayout, open: Set<RaftChannelSideSection>, section: RaftChannelSideSection): RaftColumnMoves {
    val shown = raftVisibleChannelSideColumns(layout, open)
    val at = shown.indexOfFirst { section in it.sections }
    if (at < 0) return RaftColumnMoves(null, null)
    val own = shown[at]
    val shared = own.sections.size > 1
    val left =
        when {
            shared -> RaftChannelSideDrop.NewColumn(own.column)
            at > 0 -> RaftChannelSideDrop.NewColumn(shown[at - 1].column)
            else -> null
        }
    val right =
        when {
            shared -> RaftChannelSideDrop.NewColumn(own.column + 1)
            at < shown.lastIndex -> RaftChannelSideDrop.NewColumn(shown[at + 1].column + 1)
            else -> null
        }
    fun shownSections(of: RaftChannelSideLayout) = raftVisibleChannelSideColumns(of, open).map { it.sections }
    fun ok(drop: RaftChannelSideDrop?): RaftChannelSideDrop? {
        if (drop == null) return null
        val moved = raftMoveChannelSideSection(layout, section, drop)
        return if (moved === layout || shownSections(moved) == shownSections(layout)) null else drop
    }
    return RaftColumnMoves(ok(left), ok(right))
}

/** Web `resizeChannelSideColumn`: column [column] [width] wide (rounded, within the saved range); [layout] itself when unchanged. */
internal fun raftResizeChannelSideColumn(layout: RaftChannelSideLayout, column: Int, width: Float): RaftChannelSideLayout {
    if (column < 0 || column >= layout.columns.size) return layout
    val clamped = width.coerceIn(RAFT_CHANNEL_SIDE_MIN_SAVED_WIDTH.toFloat(), RAFT_CHANNEL_SIDE_MAX_SAVED_WIDTH.toFloat()).roundToInt()
    if (layout.columns[column].width == clamped) return layout
    return RaftChannelSideLayout(layout.columns.mapIndexed { i, c -> if (i == column) c.copy(width = clamped) else c })
}

/**
 * The pane grid's arrangement of the cells it draws ([RaftPaneGridArrangement]): each section is drawn in the cell of
 * its [RaftChannelSideSection.wire] id, so the row stands and stacks them as the layout says.
 */
internal fun RaftChannelSideLayout.paneGridArrangement(): RaftPaneGridArrangement =
    RaftPaneGridArrangement(columns.map { column -> RaftPaneGridArrangementColumn(column.sections.map { it.wire }, column.width.toFloat()) })

/**
 * The side layout, per device (#189 step 4; the web keeps it on the server, a later step here), under
 * [KEY] in the web's shape. A stored value that is not a layout reads as never saved: the default. It is completed on
 * read (sections this build has that it lacks are placed), never rewritten until it is changed.
 */
@Stable
internal class RaftChannelSideLayoutStore(private val preferences: () -> SharedPreferenceStore? = { null }) {
    private val store: SharedPreferenceStore? by lazy { runCatching(preferences).getOrNull() }
    /** Moves on every change, so what reads [layout] follows it; reading never writes state. */
    private var version by mutableIntStateOf(0)
    /** The layout as read or last kept; plain memory. */
    private var cached: RaftChannelSideLayout? = null

    /** The layout as kept (completed), else the default. */
    val layout: RaftChannelSideLayout
        get() {
            version
            return cached ?: (
                raftParseChannelSideLayoutText(runCatching { store?.getString(KEY) }.getOrNull())?.let(::raftCompleteChannelSideLayout)
                    ?: RAFT_DEFAULT_CHANNEL_SIDE_LAYOUT
            ).also { cached = it }
        }

    /** Keeps [next] (nothing written when it is the layout already). */
    fun save(next: RaftChannelSideLayout) {
        if (next == layout) return
        cached = next
        runCatching { store?.putString(KEY, next.toJson().toString()) }
        version++
    }

    /** [raftMoveChannelSideSection] on the kept layout, kept. */
    fun move(section: RaftChannelSideSection, drop: RaftChannelSideDrop) = save(raftMoveChannelSideSection(layout, section, drop))

    /** [raftResizeChannelSideColumn] on the kept layout, kept. */
    fun resize(column: Int, width: Float) = save(raftResizeChannelSideColumn(layout, column, width))

    companion object {
        const val KEY = "raftPaneGrid:sideLayout"

        /** App-level: this device's layout, in the shared local preferences. */
        val App: RaftChannelSideLayoutStore = RaftChannelSideLayoutStore { raftSharedPreferenceStore() }
    }
}
