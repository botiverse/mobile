package build.raft.designsystem.components

/**
 * The give-way steps of a [RaftFitToolbar], in the order the toolbar takes them as its row
 * narrows (raft-design responsive.md › The toolbar over a list; slock web `listToolbar.ts`
 * `LIST_TOOLBAR_STEP`, raft-ui FitToolbar). Each step keeps every step before it, so one step
 * says the whole toolbar's form. A step whose part the toolbar doesn't have is skipped.
 */
internal enum class RaftFitToolbarStep {
    /** Everything shows its words. */
    None,

    /** 1 (web 10). The display controls (sort, grouping, view) show only their icons. */
    DisplayIcons,

    /** 2 (web 20). The filters show only their icons, all at once. */
    FilterIcons,

    /** 3 (web 30, only with `wrap`). The filters move to a scrolling second line, their labels back. */
    FiltersWrap,

    /** 4 (web 35). A secondary action (Invite human) shows only its icon, its words its name and tooltip. */
    SecondaryIcons,

    /** 5 (web 40). The primary action becomes +, its words its name and tooltip. */
    PrimaryPlus,

    /** 6 (web 50). The scope's segmented control becomes a Select. */
    ScopeSelect,

    /** 7 (web 60). The display controls and the secondary actions fold into ⋯ More, together. */
    DisplayMore,

    /** 8 (web 70). The search becomes its icon button, which opens the field over the scope. */
    SearchButton,
}

/**
 * What each part of a [RaftFitToolbar] measures in each of its forms, in pixels; 0 for a part
 * the toolbar doesn't have. A group's width (the filters, the display controls, the secondary
 * actions) is its items' and the gaps between them.
 */
internal data class RaftFitToolbarWidths(
    /** The gap between two parts of a line. */
    val gap: Int,
    /** The scope as a segmented control. */
    val scope: Int = 0,
    /** The scope as a Select's trigger. */
    val scopeSelect: Int = 0,
    /** The least the search field takes: its icon and whole placeholder, at most 120dp. 0: no search. */
    val searchMin: Int = 0,
    /** The search as its icon button. */
    val searchButton: Int = 0,
    val filters: Int = 0,
    val filterIcons: Int = 0,
    val display: Int = 0,
    val displayIcons: Int = 0,
    val secondary: Int = 0,
    val secondaryIcons: Int = 0,
    /** The ⋯ More button. */
    val more: Int = 0,
    val primary: Int = 0,
    /** The primary action as + (with its ▾, for a split button). */
    val primaryPlus: Int = 0,
)

/** The form each part takes at one [RaftFitToolbarStep]. */
internal data class RaftFitToolbarForms(
    val displayIcons: Boolean,
    val filterIcons: Boolean,
    /** The filters are on the second line, with their labels. */
    val filtersWrapped: Boolean,
    val secondaryIcons: Boolean,
    val primaryPlus: Boolean,
    val scopeSelect: Boolean,
    /** The display controls and the secondary actions are in More. */
    val inMore: Boolean,
    val searchButton: Boolean,
)

internal fun raftFitToolbarForms(step: RaftFitToolbarStep, wrap: Boolean): RaftFitToolbarForms {
    val wrapped = wrap && step >= RaftFitToolbarStep.FiltersWrap
    return RaftFitToolbarForms(
        displayIcons = step >= RaftFitToolbarStep.DisplayIcons,
        // Once the filters have their own line they get their labels back.
        filterIcons = step >= RaftFitToolbarStep.FilterIcons && !wrapped,
        filtersWrapped = wrapped,
        secondaryIcons = step >= RaftFitToolbarStep.SecondaryIcons,
        primaryPlus = step >= RaftFitToolbarStep.PrimaryPlus,
        scopeSelect = step >= RaftFitToolbarStep.ScopeSelect,
        inMore = step >= RaftFitToolbarStep.DisplayMore,
        searchButton = step >= RaftFitToolbarStep.SearchButton,
    )
}

/**
 * The first line's width with the parts in [forms]: each part present, a gap between two. The
 * search counts its least width (or its button); More counts only while it holds something.
 */
internal fun raftFitToolbarFirstLineWidth(widths: RaftFitToolbarWidths, forms: RaftFitToolbarForms): Int {
    val parts = ArrayList<Int>(8)
    with(widths) {
        if (scope > 0) parts += if (forms.scopeSelect) scopeSelect else scope
        if (searchMin > 0) parts += if (forms.searchButton) searchButton else searchMin
        if (filters > 0 && !forms.filtersWrapped) parts += if (forms.filterIcons) filterIcons else filters
        if (forms.inMore) {
            if (display > 0 || secondary > 0) parts += more
        } else {
            if (display > 0) parts += if (forms.displayIcons) displayIcons else display
            if (secondary > 0) parts += if (forms.secondaryIcons) secondaryIcons else secondary
        }
        if (primary > 0) parts += if (forms.primaryPlus) primaryPlus else primary
    }
    return parts.sum() + widths.gap * (parts.size - 1).coerceAtLeast(0)
}

/** Whether the toolbar of [this] widths has the part [step] changes. */
private fun RaftFitToolbarWidths.has(step: RaftFitToolbarStep, wrap: Boolean): Boolean =
    when (step) {
        RaftFitToolbarStep.None -> true
        RaftFitToolbarStep.DisplayIcons -> display > 0
        RaftFitToolbarStep.FilterIcons -> filters > 0
        RaftFitToolbarStep.FiltersWrap -> wrap && filters > 0
        RaftFitToolbarStep.SecondaryIcons -> secondary > 0
        RaftFitToolbarStep.PrimaryPlus -> primary > 0
        RaftFitToolbarStep.ScopeSelect -> scope > 0
        RaftFitToolbarStep.DisplayMore -> display > 0 || secondary > 0
        RaftFitToolbarStep.SearchButton -> searchMin > 0
    }

/**
 * raft-ui FitToolbar's measure: takes the steps one at a time, in their order, and stops at the
 * first whose first line fits [available]; when none does, the last step the toolbar has. It
 * starts from nothing taken every time, so the outcome depends on the width alone (no flicker
 * between two forms while the width holds).
 */
internal fun raftFitToolbarStep(widths: RaftFitToolbarWidths, available: Int, wrap: Boolean): RaftFitToolbarStep {
    var last = RaftFitToolbarStep.None
    for (step in RaftFitToolbarStep.entries) {
        if (!widths.has(step, wrap)) continue
        last = step
        if (raftFitToolbarFirstLineWidth(widths, raftFitToolbarForms(step, wrap)) <= available) return step
    }
    return last
}

/**
 * The search's placeholder cut to fit its field (raft-ui FitToolbarSearch): whole when it
 * [fits]; otherwise after the last whole word that fits (between any two characters in Chinese,
 * Japanese or Korean), the trailing spaces trimmed and "…" right after it, so "Search members"
 * reads "Search…", never "Search …". When not even the first word fits, the first word with its
 * "…", which the field then cuts.
 */
internal fun raftFitSearchPlaceholder(text: String, fits: (String) -> Boolean): String {
    if (text.isEmpty() || fits(text)) return text
    for (end in text.length - 1 downTo 1) {
        if (!raftBreaksBefore(text, end)) continue
        val head = text.substring(0, end).trimEnd()
        if (head.isEmpty()) continue
        val candidate = "$head…"
        if (fits(candidate)) return candidate
    }
    val trimmed = text.trimStart()
    val first =
        if (trimmed.isNotEmpty() && raftIsCjk(trimmed[0])) trimmed.substring(0, 1) else trimmed.takeWhile { !it.isWhitespace() }
    return "$first…"
}

/** A line may break before [index]: at a space, or next to a CJK character. */
private fun raftBreaksBefore(text: String, index: Int): Boolean =
    text[index].isWhitespace() || raftIsCjk(text[index - 1]) || raftIsCjk(text[index])

/** CJK ideographs, kana, Hangul and their punctuation: text that breaks between any two characters. */
internal fun raftIsCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
}
