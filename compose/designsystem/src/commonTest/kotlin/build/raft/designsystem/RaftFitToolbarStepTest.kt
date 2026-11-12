package build.raft.designsystem

import build.raft.designsystem.components.RaftFitToolbarStep
import build.raft.designsystem.components.RaftFitToolbarWidths
import build.raft.designsystem.components.raftFitSearchPlaceholder
import build.raft.designsystem.components.raftFitToolbarFirstLineWidth
import build.raft.designsystem.components.raftFitToolbarForms
import build.raft.designsystem.components.raftFitToolbarStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun everythingKeepsItsWordsWhenTheLineFits() {
        // 240 + 120 + 140 + 130 + 160 and four gaps.
        assertEquals(822, raftFitToolbarFirstLineWidth(members, raftFitToolbarForms(RaftFitToolbarStep.None, wrap = false)))
        assertEquals(RaftFitToolbarStep.None, step(members, 822))
        assertEquals(RaftFitToolbarStep.None, step(members, 1024))
    }

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
    fun aStepWithoutItsPartIsSkipped() {
        // The All tab: no Group, so step 1 has nothing to do and Invite gives way first.
        val all = members.copy(display = 0, displayIcons = 0)
        assertEquals(RaftFitToolbarStep.None, step(all, 674))
        assertEquals(RaftFitToolbarStep.SecondaryIcons, step(all, 673))
        // Invite as its icon is as wide as More would be, so it stays on the line until the search goes.
        assertEquals(RaftFitToolbarStep.ScopeSelect, step(all, 80 + 120 + 32 + 60 + 24))
        assertEquals(RaftFitToolbarStep.SearchButton, step(all, 80 + 120 + 32 + 60 + 24 - 1))
        // No secondary, no display: More never shows, and the search collapses straight after the scope.
        val bare = all.copy(secondary = 0, secondaryIcons = 0)
        assertEquals(RaftFitToolbarStep.SearchButton, step(bare, 80 + 120 + 60 + 16 - 1))
        assertFalse(raftFitToolbarFirstLineWidth(bare, raftFitToolbarForms(RaftFitToolbarStep.SearchButton, false)) > 32 + 80 + 60 + 16)
    }

    @Test
    fun filtersGoIconsThenWrapToTheirOwnLineWithTheirLabelsBack() {
        val tasks =
            RaftFitToolbarWidths(
                gap = 8,
                scope = 160,
                scopeSelect = 70,
                searchMin = 120,
                searchButton = 32,
                filters = 300,
                filterIcons = 140,
                display = 100,
                displayIcons = 50,
                primary = 120,
                primaryPlus = 32,
            )
        // 160 + 120 + 300 + 50 + 120 + 32.
        assertEquals(RaftFitToolbarStep.DisplayIcons, step(tasks, 782, wrap = true))
        assertEquals(RaftFitToolbarStep.FilterIcons, step(tasks, 781, wrap = true))
        // 160 + 120 + 50 + 120 + 24 = 474 once the filters have their own line.
        assertEquals(RaftFitToolbarStep.FiltersWrap, step(tasks, 474, wrap = true))
        val wrapped = raftFitToolbarForms(RaftFitToolbarStep.FiltersWrap, wrap = true)
        assertTrue(wrapped.filtersWrapped)
        assertFalse(wrapped.filterIcons)
        assertTrue(wrapped.displayIcons)
        // The primary becomes + only after the wrap.
        assertEquals(RaftFitToolbarStep.PrimaryPlus, step(tasks, 473, wrap = true))
        // Without wrap the filters stay on the first line and the toolbar goes on to its later steps.
        assertEquals(RaftFitToolbarStep.PrimaryPlus, step(tasks, 160 + 120 + 140 + 50 + 32 + 32))
        assertFalse(raftFitToolbarForms(RaftFitToolbarStep.SearchButton, wrap = false).filtersWrapped)
    }

    @Test
    fun theFormsOfAStepKeepEveryEarlierStep() {
        val forms = raftFitToolbarForms(RaftFitToolbarStep.ScopeSelect, wrap = false)
        assertTrue(forms.displayIcons && forms.secondaryIcons && forms.primaryPlus && forms.scopeSelect)
        assertFalse(forms.inMore || forms.searchButton)
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
