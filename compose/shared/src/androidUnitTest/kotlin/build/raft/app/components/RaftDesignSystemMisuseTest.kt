package build.raft.app.components

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import build.raft.designsystem.theme.LocalRaftMisuse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

// Robolectric: the Android Compose runtime traces through android.os.Trace.
@RunWith(RobolectricTestRunner::class)
class RaftDesignSystemMisuseTest {
    private val message = "RaftIconButton needs a name: pass tooltip or contentDescription"

    @Test
    fun failOnMisuseMakesTheDesignSystemReportFail() {
        val report = misuseReporterIn { RaftProvideDesignSystemMisuse(failOnMisuse = true, content = it) }

        val failure = assertFailsWith<IllegalStateException> { report(message) }
        assertEquals(message, failure.message)
    }

    @Test
    fun withoutFailOnMisuseTheParentReporterIsKept() {
        val reports = mutableListOf<String>()
        val report =
            misuseReporterIn { content ->
                CompositionLocalProvider(LocalRaftMisuse provides { reports += it }) {
                    RaftProvideDesignSystemMisuse(failOnMisuse = false, content = content)
                }
            }

        report(message)
        assertEquals(listOf(message), reports)
    }

    @Test
    fun releaseDefaultStaysSilent() {
        // No host configured a debug build and nothing provided a reporter: the design system's
        // silent default, so a release build keeps running.
        val report = misuseReporterIn { RaftProvideDesignSystemMisuse(failOnMisuse = false, content = it) }

        report(message)
    }

    /** The [LocalRaftMisuse] reporter seen by content composed inside [wrap]. */
    private fun misuseReporterIn(wrap: @Composable (@Composable () -> Unit) -> Unit): (String) -> Unit {
        var seen: ((String) -> Unit)? = null
        val composition = Composition(MisuseUnitApplier(), Recomposer(BroadcastFrameClock()))
        try {
            composition.setContent { wrap { seen = LocalRaftMisuse.current } }
        } finally {
            composition.dispose()
        }
        return assertNotNull(seen)
    }
}

private class MisuseUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
