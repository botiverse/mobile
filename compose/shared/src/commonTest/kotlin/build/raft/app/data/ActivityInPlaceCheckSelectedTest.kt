package build.raft.app.data

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `inbox.inplace.selected` (mobile): `1` / `0` when the open-on-screen registry is known, absent otherwise. */
class ActivityInPlaceCheckSelectedTest {
    private fun check(selected: Boolean?) =
        ActivityInPlaceCheck(
            entityId = "channel:c1",
            trigger = InboxRefreshTrigger.entries.first(),
            rowExisted = true,
            result = "match",
            outcome = ActivityInPlaceCheckOutcome.Noop,
            sampleRate = 20,
            outcomeDetail = "",
            timestampMillis = 1L,
            selected = selected,
        )

    @Test
    fun selectedIsSentAsOneOrZeroAndLeftOutWhenUnknown() {
        assertEquals("1", check(true).stateTransitionTraceAttrs()["inbox.inplace.selected"]!!.jsonPrimitive.content)
        assertEquals("0", check(false).stateTransitionTraceAttrs()["inbox.inplace.selected"]!!.jsonPrimitive.content)
        assertNull(check(null).stateTransitionTraceAttrs()["inbox.inplace.selected"])
    }
}
