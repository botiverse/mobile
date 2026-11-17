package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS unhandled-exception record must keep enough frames to symbolicate inside one
 * diagnostics line (Hands f36226a4 kept three; a Compose layout crash, b692fce7, kept ~40 of its
 * Compose frames and none of the layout that threw).
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
    fun theWholeRecordKeepsAFortyFrameStackWhole() {
        val error = IllegalStateException(composeMessage)
        val record = raftUnhandledExceptionRecord(error, "kotlin.IllegalStateException")
        assertTrue(record.reason.length <= RaftUnhandledExceptionReasonMaxChars, "reason ${record.reason.length}")
        assertTrue(record.reason.startsWith("kotlin.IllegalStateException: Horizontally scrollable"))

        val stack = raftCompactUnhandledExceptionStack(deviceStack, header)
        assertEquals(41, stack.lines().count { it.startsWith("at ") }, "every frame kept")
        assertTrue(stack.lines().none { it.contains("elided") })
    }

    /** A Compose layout crash's depth (b692fce7): far more frames than the budget holds. */
    @Test
    fun aDeepStackKeepsItsHeadAndTailWithinTheBudget() {
        val deep =
            buildString {
                append(header).append('\n')
                for (i in 0 until 600) {
                    append("    at $i   iosApp                              0x1048e4773        $bundle + ${9062259 + i} \n")
                }
            }
        val stack = raftCompactUnhandledExceptionStack(deep, header)
        assertTrue(stack.length <= RaftUnhandledExceptionStackMaxChars, "stack ${stack.length}")
        val lines = stack.lines()
        assertEquals("at 0 iosApp 0x1048e4773 iosApp + 9062259", lines.first(), "the frame that threw")
        assertEquals("at 599 iosApp 0x1048e4773 iosApp + 9062858", lines.last(), "the outermost frame")
        val marker = lines.single { it.contains("elided") }
        val kept = lines.count { it.startsWith("at ") }
        assertEquals("… ${600 - kept} lines elided …", marker)
        assertTrue(kept >= 250, "frames kept: $kept")
        // The head (where it threw) gets most of the room.
        val head = lines.indexOf(marker)
        assertTrue(head > kept - head, "head $head frames, tail ${kept - head}")
        // With the longest reason and the prefix, the line fits the native recorder's 16384.
        val line = "kuikly_unhandled_exception page=unknown mode=0 reason=${"r".repeat(RaftUnhandledExceptionReasonMaxChars)} stack=$stack"
        assertTrue(line.length <= 16384, "line ${line.length}")
    }

    @Test
    fun aStackWithoutTheHeaderIsKeptWhole() {
        val stack = raftCompactUnhandledExceptionStack("Some other header\n    at 0   iosApp  0x1  f + 1 ", "nope")
        assertEquals("Some other header\nat 0 iosApp 0x1 f + 1", stack)
    }
}
