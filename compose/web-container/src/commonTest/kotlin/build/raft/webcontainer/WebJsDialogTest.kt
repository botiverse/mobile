package build.raft.webcontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebJsDialogTest {
    @Test
    fun alertConfirmAndPromptDeliverExactlyOnceAcrossRepeatedCalls() {
        val alertResults = mutableListOf<WebJsDialogResult>()
        val alert =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Alert,
                originUrl = "https://docs.example.com/page",
                message = "Saved!",
                onResult = { alertResults += it },
            )
        assertTrue(alert.confirm())
        assertFalse(alert.cancel())
        assertFalse(alert.confirm())
        assertTrue(alert.isSettled)
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.AlertDismissed), alertResults)

        val confirmResults = mutableListOf<WebJsDialogResult>()
        val confirmYes =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://docs.example.com/page",
                message = "Delete item?",
                onResult = { confirmResults += it },
            )
        assertTrue(confirmYes.confirm())
        assertFalse(confirmYes.cancel())
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Confirm(true)), confirmResults)

        val confirmNoResults = mutableListOf<WebJsDialogResult>()
        val confirmNo =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://docs.example.com/page",
                message = "Delete item?",
                onResult = { confirmNoResults += it },
            )
        assertTrue(confirmNo.cancel())
        assertFalse(confirmNo.confirm())
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Confirm(false)), confirmNoResults)

        val promptResults = mutableListOf<WebJsDialogResult>()
        val promptOk =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Prompt,
                originUrl = "https://docs.example.com/page",
                message = "Rename file",
                defaultPromptText = "draft.md",
                onResult = { promptResults += it },
            )
        assertEquals("draft.md", promptOk.defaultPromptText)
        assertTrue(promptOk.confirm("final.md"))
        assertFalse(promptOk.cancel())
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Prompt("final.md")), promptResults)

        val promptCancelResults = mutableListOf<WebJsDialogResult>()
        val promptCancel =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Prompt,
                originUrl = "https://docs.example.com/page",
                message = "Rename file",
                defaultPromptText = "draft.md",
                onResult = { promptCancelResults += it },
            )
        assertTrue(promptCancel.cancel())
        assertFalse(promptCancel.confirm("ignored"))
        assertEquals(listOf<WebJsDialogResult>(WebJsDialogResult.Prompt(null)), promptCancelResults)
    }

    @Test
    fun trackerCancelsPreviousOnReplacementAndOnTeardown() {
        val tracker = WebJsDialogTracker()
        val outcomes = mutableListOf<String>()

        var firstRef: WebJsDialogRequest? = null
        val first =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://a.example",
                message = "First",
                onSettled = { firstRef?.let(tracker::clearIfSame) },
                onResult = { outcomes += "first:$it" },
            )
        firstRef = first
        tracker.begin(first)
        assertEquals(first, tracker.activeRequest)

        var secondRef: WebJsDialogRequest? = null
        val second =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Prompt,
                originUrl = "https://a.example",
                message = "Second",
                onSettled = { secondRef?.let(tracker::clearIfSame) },
                onResult = { outcomes += "second:$it" },
            )
        secondRef = second
        tracker.begin(second)
        assertTrue(first.isSettled)
        assertEquals(second, tracker.activeRequest)

        tracker.cancelActive()
        tracker.cancelActive()
        assertNull(tracker.activeRequest)
        assertTrue(second.isSettled)
        assertEquals(
            listOf(
                "first:Confirm(confirmed=false)",
                "second:Prompt(value=null)",
            ),
            outcomes,
        )
    }

    @Test
    fun visibilityGateRequiresOptInVisibleHostAndHandler() {
        assertTrue(isWebJsDialogPermitted(allowJsDialogs = true, isVisibleAndActive = true, hasHandler = true))
        assertFalse(isWebJsDialogPermitted(allowJsDialogs = false, isVisibleAndActive = true, hasHandler = true))
        assertFalse(isWebJsDialogPermitted(allowJsDialogs = true, isVisibleAndActive = false, hasHandler = true))
        assertFalse(isWebJsDialogPermitted(allowJsDialogs = true, isVisibleAndActive = true, hasHandler = false))
    }

    @Test
    fun oversizedAndNullByteTextIsSanitized() {
        val huge = "a".repeat(WEB_JS_DIALOG_MAX_MESSAGE_CHARS + 500) + "\u0000tail"
        val req =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Prompt,
                originUrl = "https://a.example",
                message = "hi\u0000there",
                defaultPromptText = huge,
                onResult = {},
            )
        assertEquals("hithere", req.message)
        assertEquals(WEB_JS_DIALOG_MAX_PROMPT_CHARS, req.defaultPromptText.length)
        assertFalse(req.defaultPromptText.contains('\u0000'))
    }

    @Test
    fun settledListenersFireExactlyOnceBeforeOrAfterSettlement() {
        var beforeCount = 0
        var afterCount = 0
        val req =
            WebJsDialogRequest(
                kind = WebJsDialogKind.Confirm,
                originUrl = "https://a.example",
                message = "Confirm?",
                onResult = {},
            )
        req.addOnSettledListener { beforeCount += 1 }
        assertEquals(0, beforeCount)
        assertTrue(req.cancel())
        assertEquals(1, beforeCount)
        // Repeat cancel does not re-fire listener; late registration fires immediately once.
        assertFalse(req.cancel())
        assertEquals(1, beforeCount)
        req.addOnSettledListener { afterCount += 1 }
        assertEquals(1, afterCount)
    }
}
