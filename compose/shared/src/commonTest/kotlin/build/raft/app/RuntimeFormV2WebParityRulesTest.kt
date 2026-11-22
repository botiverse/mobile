package build.raft.app

import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Renderer rules ported from the web runtime form (RuntimeFormV2FieldControl, RuntimeChoiceCombobox). */
class RuntimeFormV2WebParityRulesTest {
    private val model = RuntimeFormV2Field("model", RuntimeFormV2FieldKind.DependentSelect, required = true, advanced = false, label = "Model", optionSourceId = "model")
    private val effort = RuntimeFormV2Field("effort", RuntimeFormV2FieldKind.DerivedSelect, required = false, advanced = false, label = "Effort", optionSourceId = "model")

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
}
