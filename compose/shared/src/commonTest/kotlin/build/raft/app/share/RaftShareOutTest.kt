package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.data.RaftProductEvent
import build.raft.app.data.RaftShareActionMethod
import build.raft.app.data.RaftShareActionObject
import build.raft.app.data.RaftShareActionResult
import build.raft.app.data.RaftShareActionSurface
import build.raft.app.data.REGISTERED_SERVER_FEATURE_FLAG_KEYS
import build.raft.app.data.SHARE_LOOP_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/** Share out's rules (RaftShareOut.kt): what a sheet sends, the DM rule, links, the host command, events. */
class RaftShareOutTest {
    private fun subject(link: String? = null, text: String? = null, image: Boolean = false) =
        RaftShareOutSubject(
            obj = RaftShareActionObject.Message,
            surface = RaftShareActionSurface.MessageMenu,
            title = "design-review · Message from Kirby",
            link = link,
            text = text,
            imageAvailable = image,
        )

    private val link = "https://app.raft.build/s/botiverse/channel/0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21?msg=cf5d7bb5-6bbe-4304-bc87-1ab40601a98e"

    @Test
    fun aLinkItemDefaultsToItsLinkWithRaftsActions() {
        val android = raftShareOutPlan(subject(link, "hello", image = true), RaftShareOutPlatform.Android)
        assertEquals(RaftShareOutPayloadKind.Link, android?.primary)
        assertEquals(
            listOf(RaftShareOutAction.CopyLink, RaftShareOutAction.SendText, RaftShareOutAction.SendImage),
            android?.actions,
        )
        // iOS: the system's own Copy copies the link the sheet carries.
        assertEquals(
            listOf(RaftShareOutAction.SendText, RaftShareOutAction.SendImage),
            raftShareOutPlan(subject(link, "hello", image = true), RaftShareOutPlatform.Ios)?.actions,
        )
        // Message long-press / multi-select keep Copy link alongside Share link + text + image.
        val selection =
            RaftShareOutSubject(
                obj = RaftShareActionObject.MessageSelection,
                surface = RaftShareActionSurface.SelectionToolbar,
                title = "design-review · 2 messages",
                link = link,
                text = "**Kirby**: hello",
                imageAvailable = true,
            )
        assertEquals(
            listOf(RaftShareOutAction.CopyLink, RaftShareOutAction.SendText, RaftShareOutAction.SendImage),
            raftShareOutPlan(selection, RaftShareOutPlatform.Android)?.actions,
        )
        // Conversation / task ⋯ menus already place Copy link right next to Share, so no actions
        // are attached (Android <= 13 goes straight to the system share sheet with no second ask).
        listOf(
            RaftShareOutSubject(RaftShareActionObject.Channel, RaftShareActionSurface.ConversationMenu, "#design", link = link),
            RaftShareOutSubject(RaftShareActionObject.Lounge, RaftShareActionSurface.ConversationMenu, "Lagoon's Lounge", link = link),
            RaftShareOutSubject(RaftShareActionObject.Thread, RaftShareActionSurface.ConversationMenu, "Thread", link = link),
            RaftShareOutSubject(RaftShareActionObject.Task, RaftShareActionSurface.TaskMenu, "Ship it", link = link),
        ).forEach { menuSubject ->
            assertEquals(RaftShareOutPayloadKind.Link, raftShareOutPlan(menuSubject, RaftShareOutPlatform.Android)?.primary)
            assertEquals(emptyList(), raftShareOutPlan(menuSubject, RaftShareOutPlatform.Android)?.actions, menuSubject.obj.wire)
            assertEquals(emptyList(), raftShareOutPlan(menuSubject, RaftShareOutPlatform.Ios)?.actions, menuSubject.obj.wire)
        }
    }

    @Test
    fun pendingTaskShareSubjectBuildsTaskLinkWithoutAConversation() {
        val subject =
            raftTaskHeaderShareSubject(
                webBaseUrl = "https://staging.raft.build/",
                taskId = "cf5d7bb5-6bbe-4304-bc87-1ab40601a98e",
                title = "Pending task",
                fallbackTitle = "#all:#1",
                directMessage = false,
            )
        assertEquals(RaftShareActionObject.Task, subject?.obj)
        assertEquals(RaftShareActionSurface.TaskMenu, subject?.surface)
        assertEquals("Pending task", subject?.title)
        assertEquals("https://staging.raft.build/tasks/ScAiFjjtiUvoyEYA6Ry12u", subject?.link)
        // Blank title falls back to the task ref; Android plan goes straight to the sheet.
        val fallback =
            raftTaskHeaderShareSubject(
                webBaseUrl = null,
                taskId = "task-1",
                title = "  ",
                fallbackTitle = "#all:#1",
                directMessage = false,
            )
        assertEquals("#all:#1", fallback?.title)
        assertEquals(emptyList(), raftShareOutPlan(fallback!!, RaftShareOutPlatform.Android)?.actions)
        // DM rule & missing id return null.
        assertNull(raftTaskHeaderShareSubject(null, "task-1", "DM task", "#dm:#1", directMessage = true))
        assertNull(raftTaskHeaderShareSubject(null, " ", "No id", "#all:#1", directMessage = false))
        // Cross-channel public/joint tasks opened from a DM keep Share task; DM/unknown tasks follow the DM rule.
        assertFalse(taskPendingShareIsDirectMessage("channel", hostDirectMessage = true))
        assertFalse(taskPendingShareIsDirectMessage("joint", hostDirectMessage = true))
        assertTrue(taskPendingShareIsDirectMessage("dm", hostDirectMessage = false))
        assertTrue(taskPendingShareIsDirectMessage(null, hostDirectMessage = true))
        assertFalse(taskPendingShareIsDirectMessage(null, hostDirectMessage = false))
    }

    @Test
    fun openedNoticeWaitsForActivityResumeDropsAfterExpiryAndStaysSilentOnCancel() {
        var now = 10_000L
        val pending = RaftShareOutPendingNotice(nowMillis = { now }, maxAgeMillis = 180_000L)

        // Normal Android share: chooser pauses Raft, user picks an app while paused, notice waits until resume.
        pending.clear()
        pending.onHostPaused()
        assertNull(pending.onNotice("Opened Messaging"))
        now += 5_000L
        assertEquals("Opened Messaging", pending.onHostResumed())
        // Consumed once: a later resume does not repeat it.
        assertNull(pending.onHostResumed())

        // Returning after maxAgeMillis drops the stale notice.
        pending.clear()
        pending.onHostPaused()
        assertNull(pending.onNotice("Opened WeChat"))
        now += 180_001L
        assertNull(pending.onHostResumed())

        // Exact boundary (maxAgeMillis) is still shown.
        pending.clear()
        pending.onHostPaused()
        assertNull(pending.onNotice("Opened WeChat"))
        now += 180_000L
        assertEquals("Opened WeChat", pending.onHostResumed())

        // Cancelling never calls onNotice: resume shows nothing.
        pending.clear()
        pending.onHostPaused()
        now += 1_000L
        assertNull(pending.onHostResumed())

        // Android 14+ multi-step share (Send text -> second sheet while still paused) + fast return:
        // clearing before the second sheet does not lose pause/resume state.
        pending.clear()
        pending.onHostPaused()
        pending.clear()
        assertNull(pending.onHostResumed())
        assertEquals("Opened Messaging", pending.onNotice("Opened Messaging"))
        assertNull(pending.onHostResumed())

        // Multi-window where host stays resumed: notice shows immediately instead of waiting for a later resume.
        pending.clear()
        assertEquals("Opened Notes", pending.onNotice("Opened Notes"))
        assertNull(pending.onHostResumed())

        // Opening a new share clears any earlier unconsumed notice; blank notices are ignored.
        pending.clear()
        pending.onHostPaused()
        assertNull(pending.onNotice("Opened OldApp"))
        pending.clear()
        assertNull(pending.onHostResumed())
        pending.onHostPaused()
        assertNull(pending.onNotice("   "))
        assertNull(pending.onHostResumed())

        // Overlapping multi-activity lifecycle: B resumes before A pauses -> host stays resumed until B pauses too.
        val multiHost = RaftShareOutPendingNotice(nowMillis = { now })
        assertNull(multiHost.onHostResumed(hostId = 1))
        assertNull(multiHost.onHostResumed(hostId = 2))
        multiHost.onHostPaused(hostId = 1)
        assertEquals("Opened Notes", multiHost.onNotice("Opened Notes"))
        multiHost.onHostPaused(hostId = 2)
        assertNull(multiHost.onNotice("Opened Mail"))
        assertEquals("Opened Mail", multiHost.onHostResumed(hostId = 2))

        // Multi-window dual-Raft-activity: A and B are both resumed, B opens share sheet and pauses
        // behind the target app while A stays resumed -> notice still waits for B to resume.
        multiHost.clear()
        assertNull(multiHost.onHostResumed(hostId = 1))
        assertNull(multiHost.onHostResumed(hostId = 2))
        multiHost.onShareSheetOpened(hostId = 2)
        multiHost.onHostPaused(hostId = 2)
        assertNull(multiHost.onNotice("Opened Messaging"))
        // An unrelated resume of A does not steal B's deferred toast.
        assertNull(multiHost.onHostResumed(hostId = 1))
        assertEquals("Opened Messaging", multiHost.onHostResumed(hostId = 2))

        // Config-change recreation: B is destroyed while in the target app; recreated B' (hostId=3)
        // consumes the deferred toast on resume.
        multiHost.onShareSheetOpened(hostId = 2)
        multiHost.onHostPaused(hostId = 2)
        assertNull(multiHost.onNotice("Opened Mail"))
        multiHost.onHostDestroyed(hostId = 2)
        assertEquals("Opened Mail", multiHost.onHostResumed(hostId = 3))

        // Deep sleep / installed boottime clock: replacing the clock governs expiry across sleep.
        var bootTime = 50_000L
        multiHost.installClock { bootTime }
        multiHost.onShareSheetOpened(hostId = 3)
        multiHost.onHostPaused(hostId = 3)
        assertNull(multiHost.onNotice("Opened WeChat"))
        bootTime += 180_001L
        assertNull(multiHost.onHostResumed(hostId = 3))

        // Only Android's "Opened <App>" defers to resume; Android Copied and all iOS notices show immediately.
        assertTrue(raftShareOutShouldDeferNotice(RaftShareOutPlatform.Android, RaftShareOutNotice.Opened("Messaging")))
        assertFalse(raftShareOutShouldDeferNotice(RaftShareOutPlatform.Android, RaftShareOutNotice.Copied))
        assertFalse(raftShareOutShouldDeferNotice(RaftShareOutPlatform.Ios, RaftShareOutNotice.SharedTo("Messages")))
        assertFalse(raftShareOutShouldDeferNotice(RaftShareOutPlatform.Ios, RaftShareOutNotice.Shared))
        assertFalse(raftShareOutShouldDeferNotice(RaftShareOutPlatform.Ios, RaftShareOutNotice.Copied))
    }

    @Test
    fun aDmMessageSharesAsTextOrImageOnly() {
        val plan = raftShareOutPlan(subject(link = null, text = "hello", image = true), RaftShareOutPlatform.Android)
        assertEquals(RaftShareOutPayloadKind.Text, plan?.primary)
        assertEquals(listOf(RaftShareOutAction.SendImage), plan?.actions)
        assertFalse(plan!!.actions.contains(RaftShareOutAction.CopyLink))
        // No text (an image-only message): the long image.
        assertEquals(
            RaftShareOutPayloadKind.Image,
            raftShareOutPlan(subject(image = true), RaftShareOutPlatform.Ios)?.primary,
        )
        assertNull(raftShareOutPlan(subject(), RaftShareOutPlatform.Android))
    }

    @Test
    fun theDmRuleGivesNoLink() {
        assertFalse(raftShareOutLinkAllowed(directMessage = true))
        assertTrue(raftShareOutLinkAllowed(directMessage = false))
        assertNull(
            raftConversationShareLink("https://app.raft.build", "botiverse", "0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21", null, directMessage = true)
        )
        assertNull(raftTaskShareLink("https://app.raft.build", "cf5d7bb5-6bbe-4304-bc87-1ab40601a98e", directMessage = true))
    }

    @Test
    fun conversationLinksAreTheWebLinksOnTheEnvironmentsWebUrl() {
        val channel = "0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val parent = "cf5d7bb5-6bbe-4304-bc87-1ab40601a98e"
        assertEquals(
            "https://staging.raft.build/s/botiverse/channel/$channel",
            raftConversationShareLink("https://staging.raft.build/", "botiverse", channel, null, directMessage = false),
        )
        assertEquals(
            "https://app.raft.build/s/botiverse/channel/$channel?thread=$channel%3A$parent",
            raftConversationShareLink(null, "botiverse", channel, parent, directMessage = false),
        )
        assertNull(raftConversationShareLink(null, null, channel, null, directMessage = false))
        assertNull(raftConversationShareLink(null, "botiverse", " ", null, directMessage = false))
    }

    @Test
    fun aTaskLinkIsTheShortIdUnderTasks() {
        // slock taskRef.ts `taskPagePath`: the 22-character Base58 of the task's UUID (artifactShortId vectors).
        assertEquals(
            "https://app.raft.build/tasks/ScAiFjjtiUvoyEYA6Ry12u",
            raftTaskShareLink(null, "cf5d7bb5-6bbe-4304-bc87-1ab40601a98e", directMessage = false),
        )
        assertEquals(
            "http://localhost:5173/tasks/2QthvfPeGfDnqwr7LG6itx",
            raftTaskShareLink("http://localhost:5173/", "0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21", directMessage = false),
        )
        // `taskLinkId`: an id that is not a UUID goes as it is.
        assertEquals("https://app.raft.build/tasks/task-1", raftTaskShareLink(null, "task-1", directMessage = false))
        assertNull(raftTaskShareLink(null, " ", directMessage = false))
        assertNull(raftTaskShareLink(null, "a/b", directMessage = false))
    }

    @Test
    fun sendTextIsTheMessageAndItsAttributionAndNothingElse() {
        assertEquals("Ship it.\n— Kirby, from Raft", raftShareOutMessageText("  Ship it. ", "— Kirby, from Raft"))
        assertEquals("Ship it.", raftShareOutMessageText("Ship it.", null))
        assertNull(raftShareOutMessageText("   ", "— Kirby, from Raft"))
    }

    @Test
    fun theHostCommandCarriesOnlyThePayloadOfItsKind() {
        val command =
            raftShareOutHostCommand(
                requestId = "share-out-1",
                kind = RaftShareOutPayloadKind.Text,
                title = "t",
                link = link,
                text = "hello",
                imagePath = "/cache/x.png",
                actions = listOf(RaftShareOutHostAction(RaftShareOutAction.SendImage, "Send image")),
                chooseTitle = "Share",
                primaryLabel = "Send text",
            )
        assertEquals("shareOut", command.optString("action"))
        assertEquals("text", command.optString("kind"))
        assertEquals("hello", command.optString("text"))
        assertFalse(command.has("link"))
        assertFalse(command.has("imagePath"))
        val actions = command.optJSONArray("actions")
        assertEquals(1, actions?.length())
        assertEquals("send_image", actions?.optJSONObject(0)?.optString("id"))
        assertEquals("Send image", actions?.optJSONObject(0)?.optString("label"))
    }

    @Test
    fun hostAnswersBecomeEvents() {
        assertEquals(
            RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.SendText),
            raftShareOutHostEvent(JSONObject().put("event", "action").put("id", "send_text")),
        )
        assertEquals(
            RaftShareOutHostEvent.TargetOpened("WeChat"),
            raftShareOutHostEvent(JSONObject().put("event", "chosen").put("app", " WeChat ")),
        )
        assertEquals(
            RaftShareOutHostEvent.Completed("com.tencent.xin.sharetimeline", copied = false),
            raftShareOutHostEvent(JSONObject().put("event", "completed").put("activityType", "com.tencent.xin.sharetimeline")),
        )
        assertEquals(RaftShareOutHostEvent.Cancelled, raftShareOutHostEvent(JSONObject().put("event", "cancelled")))
        assertNull(raftShareOutHostEvent(JSONObject().put("event", "presented")))
        assertEquals(RaftShareOutHostEvent.Failed, raftShareOutHostEvent(JSONObject().put("status", "unsupported")))
        assertEquals(RaftShareOutHostEvent.Failed, raftShareOutHostEvent(null))
    }

    @Test
    fun theNoteNamesTheAppAndIsSilentWhenCancelled() {
        val linkKind = RaftShareOutPayloadKind.Link
        assertEquals(RaftShareOutNotice.Opened("WeChat"), raftShareOutNotice(RaftShareOutHostEvent.TargetOpened("WeChat"), linkKind))
        assertNull(raftShareOutNotice(RaftShareOutHostEvent.TargetOpened(""), linkKind))
        assertEquals(
            RaftShareOutNotice.SharedTo("WeChat"),
            raftShareOutNotice(RaftShareOutHostEvent.Completed("com.tencent.xin.sharetimeline", copied = false), linkKind),
        )
        assertEquals(
            RaftShareOutNotice.Shared,
            raftShareOutNotice(RaftShareOutHostEvent.Completed("com.example.unknown.share", copied = false), linkKind),
        )
        assertEquals(RaftShareOutNotice.Copied, raftShareOutNotice(RaftShareOutHostEvent.Completed("x", copied = true), linkKind))
        assertEquals(RaftShareOutNotice.Copied, raftShareOutNotice(RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.CopyLink), linkKind))
        assertNull(raftShareOutNotice(RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.SendText), linkKind))
        assertNull(raftShareOutNotice(RaftShareOutHostEvent.Cancelled, linkKind))
        assertNull(raftShareOutNotice(RaftShareOutHostEvent.Failed, linkKind))
        assertEquals(2_000L, RaftShareOutNoticeMillis)
    }

    @Test
    fun eventsCarryOnlyTheFourEnums() {
        val shared = subject(link, "hello", image = true)
        val opened = raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Link, RaftShareOutHostEvent.TargetOpened("WeChat"))!!
        val event = RaftProductEvent.ShareActionSelected(opened)
        assertEquals("share_action_selected", event.name)
        val properties = event.encodeProperties()
        assertEquals(setOf("object", "method", "surface", "result"), properties.keys)
        assertEquals("message", properties.getValue("object").jsonPrimitive.content)
        assertEquals("link", properties.getValue("method").jsonPrimitive.content)
        assertEquals("message_menu", properties.getValue("surface").jsonPrimitive.content)
        assertEquals("opened", properties.getValue("result").jsonPrimitive.content)

        val copied = raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Link, RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.CopyLink))
        assertEquals(RaftShareActionMethod.CopyLink, copied?.method)
        assertEquals(RaftShareActionResult.Copied, copied?.result)
        // Send text / Send image lead to the next sheet, which reports itself.
        assertNull(raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Link, RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.SendText)))
        assertNull(raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Link, RaftShareOutHostEvent.ActionPicked(RaftShareOutAction.SendImage)))
        val text = raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Text, RaftShareOutHostEvent.Completed("x", copied = false))
        assertEquals(RaftShareActionMethod.Text, text?.method)
        assertEquals(RaftShareActionResult.Shared, text?.result)
        assertEquals(
            RaftShareActionResult.Cancelled,
            raftShareOutEventProperties(shared, RaftShareOutPayloadKind.Image, RaftShareOutHostEvent.Cancelled)?.result,
        )
        RaftShareActionObject.entries.forEach { assertTrue(it.wire.matches(Regex("^[a-z_]+$")), it.wire) }
    }

    @Test
    fun onlyAndroidAndIosHaveTheSheet() {
        assertEquals(RaftShareOutPlatform.Android, raftShareOutPlatform("android"))
        assertEquals(RaftShareOutPlatform.Ios, raftShareOutPlatform(" iOS "))
        assertNull(raftShareOutPlatform("desktop"))
        assertNull(raftShareOutPlatform(null))
    }

    // --- The gate (share_loop_v0): off, every menu is as it was. ---

    @Test
    fun theFlagIsRegisteredAndOffByDefault() {
        assertTrue(SHARE_LOOP_FEATURE_FLAG_KEY in REGISTERED_SERVER_FEATURE_FLAG_KEYS)
        var now = 1_000L
        val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, ReadStateV1DiagnosticOverrideController { now })
        fun enabled(serverEffective: Boolean) = resolveShareLoopEnabled(registry, serverEffective)
        assertFalse(enabled(serverEffective = false))
        assertTrue(enabled(serverEffective = true))
        // A build without the diagnostics entitlement cannot force it on.
        registry.setMode(SHARE_LOOP_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertFalse(enabled(serverEffective = false))
        // A review build (entitled): Force on turns it on without a server flag, Force off wins over the server.
        registry.configureEntitlement(true)
        registry.setMode(SHARE_LOOP_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(enabled(serverEffective = false))
        // Reading the gate never changes the registry (a composition re-reads it on every revision).
        val revision = registry.revisionFor(SHARE_LOOP_FEATURE_FLAG_KEY).value
        assertTrue(enabled(serverEffective = false))
        assertTrue(enabled(serverEffective = true))
        assertEquals(revision, registry.revisionFor(SHARE_LOOP_FEATURE_FLAG_KEY).value)
        registry.setMode(SHARE_LOOP_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(enabled(serverEffective = true))
        registry.setMode(SHARE_LOOP_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertFalse(enabled(serverEffective = false))
        // The override ends with its 15 minutes.
        registry.setMode(SHARE_LOOP_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        now += 16L * 60L * 1000L
        assertFalse(enabled(serverEffective = false))
    }

    @Test
    fun withTheFlagOffTheMessageMenusAreUnchanged() {
        val message = RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Kirby", body = "Ship it.")
        val before = ThreadNewMessageMenuActionAvailability(copyLink = true, select = true)
        // No share out on the page (the flag off): the same availability, the same two rows.
        val off = before.withShareOut(shareOut = null, message = message)
        assertEquals(before, off)
        assertFalse(off.share)
        assertEquals(ThreadNewMessageMenuToolOrder, off.primaryTools())
        assertFalse(ThreadNewMessageMenuTool.Share in off.primaryTools() + off.secondaryTools())
        assertEquals(
            listOf(ThreadNewMessageMenuTool.SelectText, ThreadNewMessageMenuTool.CopyLink),
            off.secondaryTools(),
        )
        assertTrue(off.enabled(ThreadNewMessageMenuTool.CopyLink))
        assertFalse(off.enabled(ThreadNewMessageMenuTool.Share))
        // The Classic menu: no Share row without its callback.
        val classic = classicActions(message, onShare = null)
        assertFalse(classic.any { it.icon == RaftLucideIconName.Share })
        // The ⋯ menu: no rows added, none changed.
        val rows = listOf(RaftUiMenuItem(id = "a", label = "A"), RaftUiMenuItem(id = "b", label = "B"))
        assertEquals(rows, threadHeaderMenuItemsAfterShare(rows, shareCount = 0))
    }

    @Test
    fun withTheFlagOnShareFollowsForwardAndADmLosesCopyLink() {
        val before = ThreadNewMessageMenuActionAvailability(copyLink = true, select = true)
        val channel = before.withShareOut(shareable = true, directMessage = false)
        assertEquals(
            listOf(
                ThreadNewMessageMenuTool.Copy,
                ThreadNewMessageMenuTool.Reply,
                ThreadNewMessageMenuTool.Forward,
                ThreadNewMessageMenuTool.Share,
                ThreadNewMessageMenuTool.Save,
                ThreadNewMessageMenuTool.Translate,
            ),
            channel.primaryTools(),
        )
        // Select gave Share its slot and leads the second row; Copy link stays.
        assertEquals(
            listOf(ThreadNewMessageMenuTool.Select, ThreadNewMessageMenuTool.SelectText, ThreadNewMessageMenuTool.CopyLink),
            channel.secondaryTools(),
        )
        val dm = before.withShareOut(shareable = true, directMessage = true)
        assertTrue(dm.enabled(ThreadNewMessageMenuTool.Share))
        assertFalse(dm.enabled(ThreadNewMessageMenuTool.CopyLink))
        // A message that cannot be shared (a system row, a local send): the menu as before, in a channel.
        val unshareable = before.withShareOut(shareable = false, directMessage = false)
        assertEquals(ThreadNewMessageMenuToolOrder, unshareable.primaryTools())
        // A text selection's menu never has Share.
        assertFalse(channel.copy(selectionContext = true).enabled(ThreadNewMessageMenuTool.Share))
        // The Classic menu: Share right after Forward.
        val message = RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Kirby", body = "Ship it.")
        val classic = classicActions(message, onShare = {})
        val forwardAt = classic.indexOfFirst { it.icon == RaftLucideIconName.Forward }
        assertTrue(forwardAt >= 0)
        assertEquals(RaftLucideIconName.Share, classic[forwardAt + 1].icon)
        // The ⋯ menu: a separator after Share and Copy link.
        val rows = listOf("s", "c", "x", "y").map { RaftUiMenuItem(id = it, label = it) }
        assertEquals(listOf(false, false, true, false), threadHeaderMenuItemsAfterShare(rows, shareCount = 2).map { it.dividerBefore })
    }

    @Test
    fun shareMenuLabelsNameTheirObject() {
        assertEquals(RaftShareActionObject.Thread, threadConversationShareObject(replyThread = true, lounge = true))
        assertEquals(RaftShareActionObject.Lounge, threadConversationShareObject(replyThread = false, lounge = true))
        assertEquals(RaftShareActionObject.Channel, threadConversationShareObject(replyThread = false, lounge = false))
    }

    private fun classicActions(message: RaftThreadMessageItem, onShare: (() -> Unit)?): List<ThreadMessageMenuAction> =
        threadMessageMenuActions(
            message = message,
            showReplyAction = true,
            showSaveAction = true,
            showFollowAction = false,
            showTaskStatusAction = false,
            showConvertToTaskAction = false,
            showCopyLinkAction = true,
            showShareMessagesAction = true,
            showCancelAction = false,
            showDeleteAction = false,
            showForwardAction = true,
            onReply = {},
            onCopyLink = {},
            onCopy = {},
            onSelectText = {},
            onShareMessages = {},
            onSave = {},
            onFollowThread = {},
            onUnfollowThread = {},
            onTaskStatusChange = {},
            onConvertToTask = {},
            onCancel = {},
            onDelete = {},
            onShare = onShare,
        )
}
