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
    // A page that stays an Activity (batch 3 moved the chat onto the stack).
    private val browser = JsonObject(mapOf("url" to JsonPrimitive("https://example.com")))

    @Test
    fun switchOffKeepsEveryPageOnTheActivityPath() {
        listOf(SETTINGS_DETAIL to about, RaftPages.THREAD to chat("c1"), RaftPages.APP_SHELL to JsonObject(emptyMap()))
            .forEach { (name, data) ->
                assertEquals(name, RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack = null, name, data, from = null))
            }
    }

    @Test
    fun onlyTheAppShellWindowHostsTheStack() {
        // Short-circuits before the process decision: no other page ever draws the stack.
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.THREAD) { InMemoryRaftKeyValueStore() })
        assertFalse(raftAndroidNav3StackEnabled(RaftPages.LOGIN) { InMemoryRaftKeyValueStore() })
    }

    @Test
    fun switchOnRoutesTheAllowlistToTheStackAndTheRestToActivities() {
        val stack = RaftPageStack()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = null))
        stack.open(SETTINGS_DETAIL, about, from = stack.pages.first())
        val aboutPage = stack.pages.last()
        assertEquals(RaftStackOpenRoute.Stack, raftAndroidNav3OpenRoute(stack, SETTINGS_DETAIL, about, from = aboutPage))
        // Mixing: a page not on the allowlist opened from a stack page still gets an Activity.
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.IN_APP_BROWSER, browser, from = aboutPage))
        assertEquals(RaftStackOpenRoute.Host, raftAndroidNav3OpenRoute(stack, RaftPages.APP_SHELL, about, from = aboutPage))
    }

    @Test
    fun restoreTakesOnlyAllowlistedPages() {
        val stack = RaftPageStack().apply { open(SETTINGS_DETAIL, about, from = pages.first()) }
        val text = RaftPageStackSavedState.encode(stack.savedKeys())
        assertEquals(stack.savedKeys(), raftAndroidNav3RestoredKeys(text))

        val dropped = RaftPageStack().apply { open(RaftPages.IN_APP_BROWSER, browser, from = pages.first()) }
        assertNull(raftAndroidNav3RestoredKeys(RaftPageStackSavedState.encode(dropped.savedKeys())))
        assertNull(raftAndroidNav3RestoredKeys(null))
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
    fun batch1PagesOpenOnTheStackFromTheShellAndFromStackPages() {
        val stack = RaftPageStack()
        val member = JsonObject(mapOf("memberId" to JsonPrimitive("m1"), "memberKind" to JsonPrimitive("agent")))
        listOf(
            SETTINGS_DETAIL to settings("notifications"),
            SETTINGS_DETAIL to settings("developer"),
            HOME_SAVED to JsonObject(mapOf("destination" to JsonPrimitive("saved"))),
            HOME_DRAFTS to JsonObject(mapOf("destination" to JsonPrimitive("drafts"))),
            MEMBER_DETAIL to member,
        ).forEach { (name, data) ->
            assertTrue(name, stack.openLikeTheShell(name, data, from = null))
            assertTrue(name, stack.openLikeTheShell(name, data, from = stack.pages.last()))
        }
    }

    @Test
    fun backOrderSettingsSubPageThenSettingsThenShell() {
        // Phone Settings is the shell's tab: the shell (root) shows it, its rows open detail pages.
        val stack = RaftPageStack()
        assertTrue(stack.openLikeTheShell(SETTINGS_DETAIL, settings("developer"), from = null))
        val developer = stack.pages.last()
        // Developer > API environment, a Settings sub-page opened from a stack page.
        assertTrue(stack.openLikeTheShell(SETTINGS_DETAIL, settings("api-environment"), from = developer))
        assertEquals(listOf(RaftPages.APP_SHELL, SETTINGS_DETAIL, SETTINGS_DETAIL), stack.pages.map { it.args.pageName })

        assertTrue(stack.back())
        assertEquals(developer, stack.pages.last())
        assertTrue(stack.back())
        assertEquals("Back lands on the shell (its Settings tab)", listOf(RaftPages.APP_SHELL), stack.pages.map { it.args.pageName })
        assertFalse("shell alone: Back is the shell's own (Settings > Back, then move task to back)", stack.back())
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

    @Test
    fun shellArgsUnchangedWithoutRestoredPages() {
        val args = RaftCmpPageArgs(RaftPages.APP_SHELL, JsonObject(emptyMap()))
        assertSame(args, raftAndroidNav3ShellArgs(args, stack = null, savedRootTab = "more"))
        assertSame(args, raftAndroidNav3ShellArgs(args, RaftPageStack(), savedRootTab = "more"))
        val withPage = RaftPageStack().apply { openLikeTheShell(SETTINGS_DETAIL, settings("about"), from = null) }
        assertSame(args, raftAndroidNav3ShellArgs(args, withPage, savedRootTab = null))
        assertSame(args, raftAndroidNav3ShellArgs(args, withPage, savedRootTab = "home"))
    }

    @Test
    fun mixingAnActivityPageOverAStackPageLeavesTheStackAsItWas() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(MEMBER_DETAIL, JsonObject(mapOf("memberId" to JsonPrimitive("m1"))), from = null)
        val profile = stack.pages.last()
        val before = stack.savedKeys()
        // The in-app browser, a web surface not moved (onboarding): Activities over the window.
        assertFalse(stack.openLikeTheShell(RaftPages.IN_APP_BROWSER, browser, from = profile))
        assertFalse(stack.openLikeTheShell(SETTINGS_WEB, web("onboarding"), from = profile))
        assertEquals("the Activity covers the window; finishing it shows the profile again", before, stack.savedKeys())
        assertEquals(profile, stack.pages.last())
        assertTrue(stack.back())
        assertEquals(1, stack.pages.size)
    }

    // Step 2 batch 2 -------------------------------------------------------------------------------

    private fun web(surface: String) =
        JsonObject(mapOf("settingsWebViewSurface" to JsonPrimitive(surface), "slockShellInstanceId" to JsonPrimitive("shell-1")))

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
    fun batch2PagesOpenOnTheStack() {
        val stack = RaftPageStack()
        listOf(
            RaftPages.SETTINGS_ACCOUNT to scope,
            "compose_settings_hands_feedback" to scope,
            RaftPages.SETTINGS_STORAGE to scope,
            "compose_release_notes" to scope,
            "compose_settings_computers" to scope,
            SETTINGS_WEB to web("billing"),
            SETTINGS_WEB to web("administration"),
            SETTINGS_WEB to web("im-bridges"),
            SETTINGS_WEB to web("marketplace"),
            // Feedback is native: its ticket list.
            "compose_settings_feedback_tickets" to scope,
        ).forEach { (name, data) ->
            assertTrue(name, stack.openLikeTheShell(name, data, from = null))
            assertTrue(name, stack.openLikeTheShell(name, data, from = stack.pages.last()))
        }
    }

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
    fun aResultForAClosedPageIsDroppedAsCancelled() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.SETTINGS_ACCOUNT, scope, from = null)
        val account = stack.pages.last()
        val bridge = FakeActivityBridge()
        val results = results(stack)
        var answer: String? = null
        pageTransport(bridge, stack, account, results).call("threadAttachmentUpload", "{}") { answer = it }
        stack.close(account)
        bridge.answer(0, """{"code":200,"files":["avatar.png"]}""")
        assertEquals("the closed page's call ends as cancelled, never with the file", """{"code":204,"bodyText":""}""", answer)
        assertEquals(0, results.pendingCount)
    }

    @Test
    fun webViewFileChooserResultFollowsItsPage() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(SETTINGS_WEB, web("billing"), from = null)
        val web = stack.pages.last()
        val results = results(stack)
        val first = results.begin(web.contentKey, RAFT_STACK_RESULT_WEB_FILE_CHOOSER)
        assertTrue("page open: its files", results.complete(first))
        val second = results.begin(web.contentKey, RAFT_STACK_RESULT_WEB_FILE_CHOOSER)
        stack.close(web)
        assertFalse("page closed: no files (the chooser gets null)", results.complete(second))
        assertFalse("an unknown or finished request delivers nothing", results.complete(second))
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

    @Test
    fun mixingBatch2PagesWithActivities() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.SETTINGS_ACCOUNT, scope, from = null)
        val account = stack.pages.last()
        // Account > a web page on the stack; from it the in-app browser (Activity over the window).
        assertTrue(stack.openLikeTheShell(SETTINGS_WEB, web("marketplace"), from = account))
        val web = stack.pages.last()
        assertFalse(stack.openLikeTheShell(RaftPages.IN_APP_BROWSER, browser, from = web))
        assertFalse(stack.openLikeTheShell(SETTINGS_WEB, web("onboarding"), from = web))
        assertEquals(listOf(RaftPages.APP_SHELL, RaftPages.SETTINGS_ACCOUNT, SETTINGS_WEB), stack.pages.map { it.args.pageName })
        // The stack page's result routing is unaffected by the Activity over it.
        val results = results(stack)
        val id = results.begin(web.contentKey, RAFT_STACK_RESULT_WEB_FILE_CHOOSER)
        assertTrue(results.complete(id))
        assertTrue(stack.back())
        assertEquals(account, stack.pages.last())
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
    fun batch3ChatFilesAndSearchOpenOnTheStack() {
        val stack = RaftPageStack()
        // Home > a channel > its members, settings, a forwarded bundle, an attachment, an artifact,
        // a reply thread; Search > a hit's chat.
        assertTrue(stack.openLikeTheShell(RaftPages.THREAD, chat("c1"), from = null))
        val channel = stack.pages.last()
        listOf(
            "compose_thread_members" to scope,
            "compose_channel_settings" to scope,
            "compose_joint_channel_connections" to scope,
            "compose_thread_forward_bundle_page" to scope,
            FILE_PREVIEW to file,
            "compose_artifact_viewer" to scope,
            RaftPages.THREAD to chat("c1-reply", "threadId" to "t1"),
        ).forEach { (name, data) ->
            assertTrue(name, stack.openLikeTheShell(name, data, from = channel))
        }
        assertTrue(stack.openLikeTheShell(HOME_SEARCH, JsonObject(mapOf("destination" to JsonPrimitive("search"))), from = null))
        assertTrue(stack.openLikeTheShell(RaftPages.THREAD, chat("c2"), from = stack.pages.last()))
        // The in-app browser from a chat still covers the window as an Activity.
        assertFalse(stack.openLikeTheShell(RaftPages.IN_APP_BROWSER, browser, from = stack.pages.last()))
    }

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
    fun aChatOpenedInPlaceReplacesItsOpener() {
        // A chat opened in place of another (slockReplaceCurrentPage from a stack page).
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.THREAD, chat("c1"), from = null)
        val first = stack.pages.last()
        val replacing =
            JsonObject(
                chat("c2", "slockOpenWithoutAnimation" to "true") +
                    ("slockReplaceCurrentPage" to JsonPrimitive(true))
            )
        assertTrue(stack.openLikeTheShell(RaftPages.THREAD, replacing, from = first))
        assertEquals(2, stack.pages.size)
        assertFalse(first in stack.pages)
    }

    @Test
    fun processDeathRestoresAThreadKeyIdentityOnly() {
        val before = RaftPageStack()
        before.openLikeTheShell(RaftPages.THREAD, chat("c1"), from = null)
        before.openLikeTheShell(FILE_PREVIEW, file, from = before.pages.last())
        val text = RaftPageStackSavedState.encode(before.savedKeys())!!

        val after = RaftPageStack()
        val keys = raftAndroidNav3RestoredKeys(text)
        assertTrue(keys != null && after.restore(keys))
        assertEquals(before.savedKeys(), after.savedKeys())
        val thread = after.pages[1]
        assertEquals(RaftPages.THREAD, thread.args.pageName)
        assertEquals("the same route identity (payload, instance): the chat reloads its own store", chat("c1"), thread.key.pageData)
        assertEquals("same content key: its saved UI state (scroll) is found again", before.pages[1].contentKey, thread.contentKey)
        assertEquals(FILE_PREVIEW, after.pages.last().args.pageName)

        // Content never rides the key: an opener's messages are dropped before saving...
        val withContent = RaftPageStack()
        withContent.openLikeTheShell(RaftPages.THREAD, JsonObject(chat("c1") + ("messages" to JsonPrimitive("[]"))), from = null)
        assertFalse("messages" in withContent.pages.last().key.pageData)
        // ...and saved text that has some is refused whole (shell alone).
        val tampered = text.replace("\"instanceId\":\"thread-c1\"", "\"instanceId\":\"thread-c1\",\"messages\":\"[]\"")
        assertTrue(tampered != text)
        assertNull(raftAndroidNav3RestoredKeys(tampered))
    }

    @Test
    fun composerPickerAndCameraResultsReachTheChatThatAsked() {
        val stack = RaftPageStack()
        stack.openLikeTheShell(RaftPages.THREAD, chat("c1"), from = null)
        val channel = stack.pages.last()
        stack.openLikeTheShell(RaftPages.THREAD, chat("c1-reply", "threadId" to "t1"), from = channel)
        val reply = stack.pages.last()
        val bridge = FakeActivityBridge()
        val results = results(stack)
        val answers = mutableMapOf<String, String?>()
        // The reply thread's camera...
        pageTransport(bridge, stack, reply, results).call("threadAttachmentUpload", """{"channelId":"t1","capture":true}""") { answers["reply"] = it }
        bridge.answer(0, """{"code":200,"files":["camera.jpg"]}""")
        assertEquals("""{"code":200,"files":["camera.jpg"]}""", answers["reply"])
        assertNull("never the other chat's", answers["channel"])
        // ...then, back on the channel, its photo picker.
        assertTrue(stack.back())
        pageTransport(bridge, stack, channel, results).call("threadAttachmentUpload", """{"channelId":"c1","imageOnly":true}""") { answers["channel"] = it }
        bridge.answer(1, """{"code":200,"files":["photo.png"]}""")
        assertEquals("""{"code":200,"files":["photo.png"]}""", answers["channel"])

        // The chat closed while the file picker was up (channel deleted: closePage): cancelled.
        pageTransport(bridge, stack, channel, results).call("threadAttachmentUpload", """{"channelId":"c1"}""") { answers["closed"] = it }
        stack.close(channel)
        bridge.answer(2, """{"code":200,"files":["late.pdf"]}""")
        assertEquals("""{"code":204,"bodyText":""}""", answers["closed"])
        assertEquals(0, results.pendingCount)
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
