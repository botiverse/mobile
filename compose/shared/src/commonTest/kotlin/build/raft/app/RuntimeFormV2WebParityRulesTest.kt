package build.raft.app

import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choice
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renderer rules ported from the web runtime form (RuntimeFormV2FieldControl, RuntimeChoiceCombobox). */
class RuntimeFormV2WebParityRulesTest {
    private val refresh = RuntimeFormV2SourceRefresh(refreshing = emptySet(), failed = emptySet(), onRefresh = {})
    private val model = RuntimeFormV2Field("model", RuntimeFormV2FieldKind.DependentSelect, required = true, advanced = false, label = "Model", optionSourceId = "model")
    private val effort = RuntimeFormV2Field("effort", RuntimeFormV2FieldKind.DerivedSelect, required = false, advanced = false, label = "Effort", optionSourceId = "model")

    @Test
    fun theLabelRowRefreshAndTheStatusRetryNeverBothShow() {
        val source = runtimeFormV2RefreshableSource(model, refresh)
        assertEquals("model", source)
        // Live, or a status whose retry cannot help (missing_config): the label-row refresh.
        assertTrue(runtimeFormV2ShowsRefreshAction(source, null))
        assertTrue(runtimeFormV2ShowsRefreshAction(source, RuntimeFormV2SourceStatus(unavailable = false, reason = "missing_config", retryable = false)))
        // A retryable status already has Retry in its line.
        assertFalse(runtimeFormV2ShowsRefreshAction(source, RuntimeFormV2SourceStatus(unavailable = true, reason = "machine_offline", retryable = true)))
        assertFalse(runtimeFormV2ShowsRefreshAction(source, RuntimeFormV2SourceStatus(unavailable = false, reason = "probe_timeout", retryable = true, stale = true)))
        // A derived select follows its parent; without refresh wiring nothing is offered.
        assertNull(runtimeFormV2RefreshableSource(effort, refresh))
        assertNull(runtimeFormV2RefreshableSource(model, null))
    }

    @Test
    fun aSavedValueNotInTheListSaysSoUnlessItWasTypedOrIsAConnection() {
        val retired = RuntimeFormV2UnavailableChoice("retired", "Retired", reason = "Disabled by the runtime")
        val connection = RuntimeFormV2UnavailableChoice("connection:c1", "DeepSeek (work)", connectionId = "c1")
        assertTrue(runtimeFormV2ShowsSavedValueNotInList(searchable = true, allowCustom = false, unlisted = true, unavailableSelected = null))
        // An unavailable entry still says it (web `unlisted && !customValue`).
        assertTrue(runtimeFormV2ShowsSavedValueNotInList(searchable = true, allowCustom = false, unlisted = true, unavailableSelected = retired))
        assertFalse(runtimeFormV2ShowsSavedValueNotInList(searchable = true, allowCustom = false, unlisted = true, unavailableSelected = connection))
        // A typed value on a list that takes one is the person's own (marked Custom), not lost.
        assertFalse(runtimeFormV2ShowsSavedValueNotInList(searchable = true, allowCustom = true, unlisted = true, unavailableSelected = null))
        assertFalse(runtimeFormV2ShowsSavedValueNotInList(searchable = true, allowCustom = false, unlisted = false, unavailableSelected = null))
        // A plain select (no search) has no such line on the web.
        assertFalse(runtimeFormV2ShowsSavedValueNotInList(searchable = false, allowCustom = false, unlisted = true, unavailableSelected = null))
    }

    @Test
    fun aChoiceDescriptionIsItsListRowsSecondLine() {
        val described = runtimeFormV2ListedOption(RuntimeFormV2Choice("high", "High", description = "Thinks longer"), group = null, showsIdentifiers = false)
        assertEquals("Thinks longer", described.description)
        // Only when it says more than the label (web `addsToLabel`, case-insensitive).
        val echo = runtimeFormV2ListedOption(RuntimeFormV2Choice("gpt-6-astra", "GPT-6-Astra", description = "gpt-6-astra"), group = null, showsIdentifiers = true)
        assertNull(echo.description)
        assertEquals("gpt-6-astra", echo.identifier)
    }
}
