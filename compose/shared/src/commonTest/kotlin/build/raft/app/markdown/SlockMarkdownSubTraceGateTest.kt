package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.SlockMarkdownSubTrace
import com.tencent.kuiklybase.markdown.compose.slockMarkdownLogSubTrace
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Teeth for the SlockMarkdownSpike println gate (task #20 optimization). The
 * channel used to be an unconditional raw println, so production paid message
 * interpolation + annotation scans + a logcat round-trip per text composition.
 * The contract under test: with the gate OFF the message lambda must not be
 * evaluated at all — that is where the real cost lives, not in the println.
 */
class SlockMarkdownSubTraceGateTest {
    @Test
    fun disabledGateDoesNotEvaluateMessageLambda() {
        val prior = SlockMarkdownSubTrace.enabled
        try {
            SlockMarkdownSubTrace.enabled = false
            var evaluated = false
            slockMarkdownLogSubTrace {
                evaluated = true
                "must-not-run"
            }
            assertFalse(evaluated, "message lambda ran with the gate OFF")
        } finally {
            SlockMarkdownSubTrace.enabled = prior
        }
    }

    @Test
    fun enabledGateEvaluatesMessageLambda() {
        val prior = SlockMarkdownSubTrace.enabled
        try {
            SlockMarkdownSubTrace.enabled = true
            var evaluated = false
            slockMarkdownLogSubTrace {
                evaluated = true
                "gate-on-line"
            }
            assertTrue(evaluated, "message lambda skipped with the gate ON")
        } finally {
            SlockMarkdownSubTrace.enabled = prior
        }
    }
}
