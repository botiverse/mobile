package build.raft.designsystem

import build.raft.designsystem.components.RaftFitToolbarStep
import build.raft.designsystem.components.RaftFitToolbarWidths
import build.raft.designsystem.components.raftFitSearchPlaceholder
import build.raft.designsystem.components.raftFitToolbarStep
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RaftFitToolbar's give-way order (raft-design responsive.md › The toolbar over a list; slock
 * `LIST_TOOLBAR_STEP`): given what each part measures and the room, the first step that fits.
 */
class RaftFitToolbarStepTest {
    /** Members on the Agents tab: scope, search, Group, Invite human, More, Create agent ▾ (gap 8). */
    private val members =
        RaftFitToolbarWidths(
            gap = 8,
            scope = 240,
            scopeSelect = 80,
            searchMin = 120,
            searchButton = 32,
            display = 140,
            displayIcons = 54,
            secondary = 130,
            secondaryIcons = 32,
            more = 32,
            primary = 160,
            primaryPlus = 60,
        )

    private fun step(widths: RaftFitToolbarWidths, available: Int, wrap: Boolean = false) = raftFitToolbarStep(widths, available, wrap)

    @Test
    fun thePartsGiveWayInTheirOrder() {
        assertEquals(RaftFitToolbarStep.DisplayIcons, step(members, 821))
        // 240 + 120 + 54 + 130 + 160 + 32 = 736.
        assertEquals(RaftFitToolbarStep.DisplayIcons, step(members, 736))
        assertEquals(RaftFitToolbarStep.SecondaryIcons, step(members, 735))
        // 240 + 120 + 54 + 32 + 160 + 32 = 638.
        assertEquals(RaftFitToolbarStep.PrimaryPlus, step(members, 637))
        // 240 + 120 + 54 + 32 + 60 + 32 = 538.
        assertEquals(RaftFitToolbarStep.ScopeSelect, step(members, 537))
        // 80 + 120 + 54 + 32 + 60 + 32 = 378.
        assertEquals(RaftFitToolbarStep.ScopeSelect, step(members, 378))
        // Group and Invite fold into More together: 80 + 120 + 32 + 60 + 24 = 316.
        assertEquals(RaftFitToolbarStep.DisplayMore, step(members, 377))
        assertEquals(RaftFitToolbarStep.DisplayMore, step(members, 316))
        // Last, the search becomes its button: 32 + 80 + 32 + 60 + 24 = 228.
        assertEquals(RaftFitToolbarStep.SearchButton, step(members, 315))
        assertEquals(RaftFitToolbarStep.SearchButton, step(members, 228))
    }

    @Test
    fun theLastStepHoldsWhenNothingFits() {
        assertEquals(RaftFitToolbarStep.SearchButton, step(members, 100))
        assertEquals(RaftFitToolbarStep.ScopeSelect, step(RaftFitToolbarWidths(gap = 8, scope = 300, scopeSelect = 200), 10))
    }

    @Test
    fun aLongPlaceholderIsCutAfterAWholeWordWithNoSpaceBeforeTheEllipsis() {
        // A character is 10 wide; room for 8.
        val fits = { text: String -> text.length <= 8 }
        assertEquals("Search…", raftFitSearchPlaceholder("Search members", fits))
        assertEquals("Search", raftFitSearchPlaceholder("Search", fits))
        assertEquals("Find a…", raftFitSearchPlaceholder("Find a member", fits))
        // Not even a word: the first word, which the field cuts.
        assertEquals("Searching…", raftFitSearchPlaceholder("Searching everything", { it.length <= 4 }))
        // Chinese and Japanese break between any two characters.
        assertEquals("搜索…", raftFitSearchPlaceholder("搜索成员列表", { it.length <= 3 }))
        assertEquals("メンバ…", raftFitSearchPlaceholder("メンバーを検索", { it.length <= 4 }))
    }
}
