package build.raft.app.markdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import build.raft.mermaid.core.MermaidParseResult
import build.raft.mermaid.core.MermaidParser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards that `MermaidParser.<clinit>` completes under the real Android ICU
 * regex engine on a running device/emulator — not the host JDK Pattern engine.
 *
 * Background (mermaid-not-rendering incident): every parser regex is built
 * eagerly inside the class's static initializer. Android's ICU engine accepts
 * a narrower grammar than the JVM Pattern engine, so a pattern that compiles
 * on the JVM throws `PatternSyntaxException` at `<clinit>` on Android. Because
 * the initializer is all-or-nothing, one bad pattern poisons the class for the
 * whole process and every later reference fails with `NoClassDefFoundError` —
 * which is why mermaid silently fell back to a code block on-device while JVM
 * tests stayed green.
 *
 * `<clinit>` being all-or-nothing is the whole point: a single touch of any
 * member covers every pattern at once. This test does not assert on the parse
 * result — it only needs the class to initialize on the real runtime. Any
 * future regex construct ICU cannot compile turns this red on the emulator,
 * while the host-side unit tests stay green.
 *
 * This belongs to the androidInstrumentedTest source set on purpose: the
 * failure mode it guards against only exists on the Android regex engine.
 */
@RunWith(AndroidJUnit4::class)
class SlockMermaidParserClinitConnectedTest {
    @Test
    fun mermaidParserInitializesOnAndroidIcuRegex() {
        // A supported minimal input keeps the post-init assertion trivially
        // satisfiable; the real signal is that <clinit> did not throw.
        val result = MermaidParser.parse("graph TD\nA-->B\n")
        assertTrue(
            "MermaidParser should initialize under the Android ICU regex engine",
            result is MermaidParseResult,
        )
    }
}
