package build.raft.app

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftSearchRowPressDelayTest {
    /** Records each interaction with the virtual time it landed at. */
    private class RecordingInteractionSource(private val scope: TestScope) : MutableInteractionSource {
        private val startedAt = scope.testScheduler.currentTime
        val events = mutableListOf<Pair<Long, String>>()
        override val interactions: Flow<Interaction> = emptyFlow()

        override suspend fun emit(interaction: Interaction) {
            tryEmit(interaction)
        }

        override fun tryEmit(interaction: Interaction): Boolean {
            val name =
                when (interaction) {
                    is PressInteraction.Press -> "press"
                    is PressInteraction.Release -> "release"
                    is PressInteraction.Cancel -> "cancel"
                    else -> interaction.toString()
                }
            events += (scope.testScheduler.currentTime - startedAt) to name
            return true
        }
    }

    @Test
    fun pressFillWaitsForTheTapTimeoutAndNeverShowsForAScroll() = runTest {
        data class Case(val name: String, val holdMillis: Long, val released: Boolean, val expected: List<Pair<Long, String>>)
        listOf(
            // A quick tap still gets its press, at release, like clickable.
            Case("tap released before the timeout", 40, true, listOf(40L to "press", 40L to "release")),
            // The list took the touch as a scroll inside the timeout: no flash at all.
            Case("scroll inside the timeout", 40, false, emptyList()),
            Case("held past the timeout then released", 150, true, listOf(100L to "press", 150L to "release")),
            Case("held past the timeout then scrolled", 150, false, listOf(100L to "press", 150L to "cancel")),
        ).forEach { case ->
            val source = RecordingInteractionSource(this)
            raftDelayedRowPress(Offset.Zero, source, this) {
                delay(case.holdMillis)
                case.released
            }
            // Nothing lands later either (the delayed press was cancelled, not just outrun).
            testScheduler.advanceUntilIdle()
            assertEquals(case.expected, source.events, case.name)
        }
        assertEquals(100L, RaftSearchRowPressDelayMillis)
    }
}
