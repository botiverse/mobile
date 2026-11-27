package build.raft.app.shared

import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackSavedState
import build.raft.app.nav.RaftStackOpenRoute
import build.raft.app.nav.RaftStackPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation 3 step 1 in the Android shell: the switch off (no stack) keeps every open on the
 * Activity path; on, only the allowlist goes onto the stack; restore takes only allowlisted keys.
 */
class RaftAndroidNav3StackTest {
    private val about =
        JsonObject(mapOf("settingsPage" to JsonPrimitive("about"), "slockShellInstanceId" to JsonPrimitive("shell-1")))
    // Installed Raft App in-app browser pushes onto the stack; login remains an Activity.
    private val appBrowser =
        JsonObject(mapOf("url" to JsonPrimitive("https://stamp.build/pulls/1"), "tabId" to JsonPrimitive("1")))
    private val outboundChild =
        JsonObject(mapOf("url" to JsonPrimitive("https://docs.example.org/spec"), "outbound" to JsonPrimitive(true)))

    @Test
    fun switchOffKeepsEveryPageOnTheActivityPath() {
        listOf(SETTINGS_DETAIL to about, RaftPages.THREAD to chat("c1"), RaftPages.APP_SHELL to JsonObject(emptyMap()))
            .forEach { (name, data) ->
                assertEquals(name, RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack = null, name, data, from = null))
            }
    }

    @Test
    fun switchOnRoutesTheAllowlistToTheStackAndTheRestToActivities() {
        val stack = RaftPageStack()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = null))
        stack.open(SETTINGS_DETAIL, about, from = stack.pages.first())
        val aboutPage = stack.pages.last()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = aboutPage))
        // In-app Raft App browser and its cross-origin outbound child page push onto the stack.
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, RaftPages.IN_APP_BROWSER, appBrowser, from = aboutPage))
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, RaftPages.IN_APP_BROWSER, outboundChild, from = aboutPage))
        // Mixing: a page not on the allowlist opened from a stack page still gets an Activity.
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.LOGIN, JsonObject(emptyMap()), from = aboutPage))
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.APP_SHELL, about, from = aboutPage))
    }

    @Test
    fun aStackPageClosePageClosesThePageNotTheWindow() {
        val calls = mutableListOf<String>()
        var closed = 0
        val transport =
            RaftStackPageBridgeTransport(
                delegate = HostBridgeTransport { method, _, _ -> calls += method },
                onClosePage = { closed++ },
            )
        transport.call("closePage", "{}", null)
        transport.call("toast", "{}", null)
        assertEquals(1, closed)
        assertEquals(listOf("toast"), calls)
    }

    // Step 2 batch 1 -------------------------------------------------------------------------------

    private fun settings(page: String, vararg extra: Pair<String, String>) =
        JsonObject(
            mapOf("settingsPage" to JsonPrimitive(page), "slockShellInstanceId" to JsonPrimitive("shell-1")) +
                extra.associate { (k, v) -> k to JsonPrimitive(v) }
        )

    /** Opens [pageName] the way RaftCmpActivity.openPageFrom does; true when it went onto the stack. */
    private fun RaftPageStack.openLikeTheShell(pageName: String, pageData: JsonObject, from: RaftStackPage?): Boolean {
        if (raftAndroidNav3OpenRoute(this, pageName, pageData, from) != RaftStackOpenRoute.Stack) return false
        open(pageName, pageData, from = from ?: pages.first())
        return true
    }

    @Test
    fun processDeathRestoresSettingsTabAndSubPages() {
        val before = RaftPageStack()
        before.openLikeTheShell(SETTINGS_DETAIL, settings("diagnostics"), from = null)
        before.openLikeTheShell(SETTINGS_DETAIL, settings("local-feature-overrides"), from = before.pages.last())
        val savedText = RaftPageStackSavedState.encode(before.savedKeys())
        val savedRootTab = "more" // the phone Settings tab, written by the shell (RaftStackRootTab)

        val after = RaftPageStack()
        val keys = raftAndroidNav3RestoredKeys(savedText)
        assertTrue(keys != null && after.restore(keys))
        assertEquals("both Settings pages come back, in order", before.savedKeys(), after.savedKeys())
        assertEquals(
            listOf("diagnostics", "local-feature-overrides"),
            after.pages.drop(1).map { (it.key.pageData["settingsPage"] as JsonPrimitive).content },
        )
        val shellArgs = raftAndroidNav3ShellArgs(RaftCmpPageArgs(RaftPages.APP_SHELL, JsonObject(emptyMap())), after, savedRootTab)
        assertEquals("the shell comes back on Settings, not Home", JsonPrimitive("more"), shellArgs.pageData["slockInitialTab"])

        // Back after the restore: sub-page, sub-page, then the shell on its Settings tab.
        assertTrue(after.back())
        assertTrue(after.back())
        assertFalse(after.back())
    }

    // Step 2 batch 2 -------------------------------------------------------------------------------

    private val scope = JsonObject(mapOf("slockShellInstanceId" to JsonPrimitive("shell-1")))

    /** The shell Activity's bridge: keeps each call's callback, [answer] plays the Activity result. */
    private class FakeActivityBridge : HostBridgeTransport {
        val calls = mutableListOf<Pair<String, (String?) -> Unit>>()

        override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
            if (callback != null) calls += method to callback
        }

        fun answer(index: Int, result: String) = calls[index].second(result)
    }

    private fun results(stack: RaftPageStack) = RaftStackActivityResults(isOpen = { key -> stack.pages.any { it.contentKey == key } })

    private fun pageTransport(bridge: HostBridgeTransport, stack: RaftPageStack, page: RaftStackPage, results: RaftStackActivityResults) =
        RaftStackPageBridgeTransport(bridge, onClosePage = { stack.close(page) }, pageKey = page.contentKey, results = results)

    @Test
    fun eachPagesPickerResultReachesThePageThatAsked() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.SETTINGS_ACCOUNT, scope, from = null)
        val account = stack.pages.last()
        stack.openLikeTheShell("compose_settings_hands_feedback", scope, from = account)
        val feedback = stack.pages.last()
        val bridge = FakeActivityBridge()
        val results = results(stack)
        val answers = mutableMapOf<String, String?>()
        pageTransport(bridge, stack, account, results).call("threadAttachmentUpload", "{}") { answers["account"] = it }
        pageTransport(bridge, stack, feedback, results).call("threadAttachmentUpload", "{}") { answers["feedback"] = it }
        // Other calls are not result routed.
        pageTransport(bridge, stack, feedback, results).call("toast", "{}") { answers["toast"] = it }
        assertEquals(2, results.pendingCount)

        bridge.answer(1, """{"code":200,"files":["feedback.png"]}""")
        bridge.answer(0, """{"code":200,"files":["avatar.png"]}""")
        assertEquals("""{"code":200,"files":["avatar.png"]}""", answers["account"])
        assertEquals("""{"code":200,"files":["feedback.png"]}""", answers["feedback"])
        assertEquals(0, results.pendingCount)
    }

    @Test
    fun processDeathWithAPendingPickDropsTheResultSafely() {
        // Settings > Account > avatar picker open, then the process dies.
        val before = RaftPageStack()
        before.openLikeTheShell(RaftPages.SETTINGS_ACCOUNT, scope, from = null)
        val account = before.pages.last()
        val beforeResults = results(before)
        pageTransport(FakeActivityBridge(), before, account, beforeResults).call("threadAttachmentUpload", "{}") { }
        val savedKeys = RaftPageStackSavedState.encode(before.savedKeys())
        val savedResults = beforeResults.encode()

        val after = RaftPageStack()
        assertTrue(after.restore(raftAndroidNav3RestoredKeys(savedKeys)!!))
        val afterResults = results(after).apply { restore(savedResults) }
        assertEquals(1, afterResults.pendingCount)
        // The pick comes back to the new instance: nothing waits for it, it is dropped for Account.
        assertEquals(account.contentKey, afterResults.dropRecreated(RAFT_STACK_RESULT_ATTACHMENT_PICKER))
        assertEquals(0, afterResults.pendingCount)
        assertNull("a second result finds nothing", afterResults.dropRecreated(RAFT_STACK_RESULT_ATTACHMENT_PICKER))
        // The restored Account page asks again: a fresh request, delivered to it.
        val bridge = FakeActivityBridge()
        var answer: String? = null
        pageTransport(bridge, after, after.pages.last(), afterResults).call("threadAttachmentUpload", "{}") { answer = it }
        bridge.answer(0, """{"code":200}""")
        assertEquals("""{"code":200}""", answer)

        // Saved pages unusable (shell alone): the saved request still ends, no page gets the result.
        val shellOnly = RaftPageStack()
        val shellResults = results(shellOnly).apply { restore(savedResults) }
        assertEquals(account.contentKey, shellResults.dropRecreated(RAFT_STACK_RESULT_ATTACHMENT_PICKER))
        // Garbage saved text restores nothing.
        assertEquals(0, results(shellOnly).apply { restore("not json") }.pendingCount)
        assertNull(results(shellOnly).encode())
    }

    @Test
    fun signOutFromAccountClearsTheStack() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(SETTINGS_DETAIL, settings("developer"), from = null)
        stack.openLikeTheShell(RaftPages.SETTINGS_ACCOUNT, scope, from = stack.pages.last())
        val account = stack.pages.last()
        val results = results(stack)
        results.begin(account.contentKey, RAFT_STACK_RESULT_ATTACHMENT_PICKER)
        // Logout opens the login page in place of the window: the host's way (login replacement).
        val logout = JsonObject(mapOf("slockReplaceCurrentPage" to JsonPrimitive(true)))
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.LOGIN, logout, from = account))

        raftAndroidNav3ClearForWindowReplace(stack, results)
        assertEquals(listOf(RaftPages.APP_SHELL), stack.pages.map { it.args.pageName })
        assertTrue(stack.savedKeys().isEmpty())
        assertEquals(0, results.pendingCount)
    }

    // Step 2 batch 3 -------------------------------------------------------------------------------

    /** A conversation's route as the shell opens it: identity and intent only (threadLaunchPageData). */
    private fun chat(channelId: String, vararg extra: Pair<String, String>) =
        JsonObject(
            mapOf(
                "payload" to JsonPrimitive("{\"instanceId\":\"thread-$channelId\",\"surfaceKind\":\"channel\",\"threadId\":\"$channelId\",\"selectedTab\":\"chat\"}"),
                "instanceId" to JsonPrimitive("thread-$channelId"),
                "slockShellInstanceId" to JsonPrimitive("shell-1"),
            ) + extra.associate { (k, v) -> k to JsonPrimitive(v) }
        )

    private val file =
        JsonObject(
            mapOf(
                "attachmentId" to JsonPrimitive("a1"),
                "previewKind" to JsonPrimitive("image"),
                "slockShellInstanceId" to JsonPrimitive("shell-1"),
            )
        )

    @Test
    fun backOrderFilePreviewThenChatThenShell() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.THREAD, chat("c1"), from = null)
        val channel = stack.pages.last()
        stack.openLikeTheShell(FILE_PREVIEW, file, from = channel)
        assertEquals(listOf(RaftPages.APP_SHELL, RaftPages.THREAD, FILE_PREVIEW), stack.pages.map { it.args.pageName })
        assertTrue(stack.back())
        assertSame("Back from the file lands on its chat", channel, stack.pages.last())
        assertTrue(stack.back())
        assertFalse("shell alone: the shell's own Back", stack.back())
    }

    @Test
    fun aNotificationOpensTheChatOnTheStackOverTheShell() {
        // A notification / share / deep link is a new Intent: the shell recreates (stack dropped, as
        // singleTask cleared the Activities) and its navigation coordinator opens the chat from the shell.
        val stack = RaftPageStack()
        assertTrue(stack.openLikeTheShell(RaftPages.THREAD, chat("c9", "focusedMessageId" to "m1"), from = null))
        assertEquals(listOf(RaftPages.APP_SHELL, RaftPages.THREAD), stack.pages.map { it.args.pageName })
        assertTrue(stack.back())
        assertEquals("Back from the notification's chat lands on the shell", 1, stack.pages.size)
    }

    private companion object {
        const val FILE_PREVIEW = "compose_file_preview"
        const val HOME_SEARCH = "compose_home_search"
        const val SETTINGS_DETAIL = "compose_settings_native_detail"
        const val HOME_SAVED = "compose_home_saved"
        const val HOME_DRAFTS = "compose_home_drafts"
        const val MEMBER_DETAIL = "compose_member_detail"
        const val SETTINGS_WEB = "compose_settings_webview"
    }
}
