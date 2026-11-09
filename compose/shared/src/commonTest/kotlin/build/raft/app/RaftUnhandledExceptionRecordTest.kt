package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS unhandled-exception record must keep enough frames to symbolicate inside one
 * 2048-character diagnostics line (Hands f36226a4 kept three).
 */
class RaftUnhandledExceptionRecordTest {
    private val composeMessage =
        "Horizontally scrollable component was measured with an infinity maximum width constraints, " +
            "which is disallowed. ".repeat(40)
    private val header = "kotlin.IllegalStateException: $composeMessage"
    private val bundle = "/private/var/containers/Bundle/Application/3011B72D-8F36-4173-B0D3-3AB6FF72920B/iosApp.app/iosApp"
    private val mangled =
        "_ZN6kotlin14ManuallyScopedINS_17ParallelProcessorINS_22intrusive_forward_listINS_2gc2GC10Object" +
            "DataENS_33DefaultIntrusiveForwardListTraitsIS5_EEEELm512ELm4096EEELb0EE4implEv"

    /** A Kotlin/Native `stackTraceToString()` as the device produced it (f36226a4), 40 frames deep. */
    private val deviceStack =
        buildString {
            append(header).append('\n')
            append("    at 0   iosApp                              0x10a03f9db        $mangled + 6337751 \n")
            for (i in 1 until 40) {
                append("    at $i   iosApp                              0x1048e4773        $bundle + ${9062259 + i} \n")
            }
            append("Caused by: kotlin.IllegalArgumentException: ").append("x".repeat(500)).append('\n')
            append("    at 40   iosApp                              0x1049cd02f        kfun:foo.Bar#baz(){} + 12 \n")
        }

    @Test
    fun theStackDropsTheRepeatedMessageAndCompactsEachFrame() {
        val stack = raftCompactUnhandledExceptionStack(deviceStack, header)
        val lines = stack.lines()
        assertEquals("at 0 iosApp 0x10a03f9db", lines[0])
        assertEquals("at 1 iosApp 0x1048e4773 iosApp + 9062260", lines[1])
        assertTrue(lines.none { it.contains("Horizontally scrollable") }, "message repeated in the stack")
        assertTrue(lines.none { it.contains("/private/var/containers") }, "bundle path kept")
        val cause = lines.single { it.startsWith("Caused by:") }
        assertTrue(cause.length <= 200, "cause line clipped: ${cause.length}")
        assertEquals("at 40 iosApp 0x1049cd02f kfun:foo.Bar#baz(){} + 12", lines.last())
    }

    @Test
    fun theWholeRecordKeepsAboutFortyFramesInOneDiagnosticsLine() {
        val error = IllegalStateException(composeMessage)
        val record = raftUnhandledExceptionRecord(error, "kotlin.IllegalStateException")
        assertTrue(record.reason.length <= RaftUnhandledExceptionReasonMaxChars, "reason ${record.reason.length}")
        assertTrue(record.reason.startsWith("kotlin.IllegalStateException: Horizontally scrollable"))

        val stack = raftCompactUnhandledExceptionStack(deviceStack, header)
        val line = "kuikly_unhandled_exception page=unknown mode=0 reason=${record.reason} stack=$stack"
        // The native recorder clips the line at 2048 characters: count the frames that survive.
        val kept = line.take(2048).lines().count { it.startsWith("at ") || it.contains("stack=at ") }
        assertTrue(kept >= 35, "frames kept in 2048 chars: $kept")
    }

    @Test
    fun aStackWithoutTheHeaderIsKeptWhole() {
        val stack = raftCompactUnhandledExceptionStack("Some other header\n    at 0   iosApp  0x1  f + 1 ", "nope")
        assertEquals("Some other header\nat 0 iosApp 0x1 f + 1", stack)
    }
}
