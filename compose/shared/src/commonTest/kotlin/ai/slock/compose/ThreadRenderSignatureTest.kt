package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Focused regression coverage for [ThreadRenderSignature] / [KuiklyThreadScreenPayload.toRenderSignature].
 *
 * The signature is the key used to drive the `thread_render` perf marker
 * in `ThreadPage`. These tests pin the equivalence classes we care about:
 *
 * - members / settings / actions / realtime metadata changes must NOT
 *   change the signature (otherwise non-render axis churn retriggers
 *   render log hot path)
 * - message/task/status/tab/showReplyActions changes that actually
 *   affect the visible render axis MUST change the signature
 * - equivalent messages list content (same id sequence, same reply
 *   counts) but different list references MUST produce the same
 *   signature
 */
class ThreadRenderSignatureTest {

    private fun message(
        id: String,
        replyCount: Int = 0,
        unreadReplyCount: Int = 0
    ): KuiklyThreadMessageItem = KuiklyThreadMessageItem(
        id = id,
        authorId = "user-$id",
        authorName = "User $id",
        body = "body-$id",
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount
    )

    private fun payload(
        instanceId: String = "iid",
        sourceThreadId: String? = "src",
        status: String = "content",
        selectedTab: String = "chat",
        messages: List<KuiklyThreadMessageItem> = emptyList(),
        tasks: List<KuiklyThreadTaskItem> = emptyList(),
        showReplyActions: Boolean = true,
        members: List<KuiklyThreadMemberItem> = emptyList(),
        settingsActions: List<KuiklyThreadSettingsAction> = emptyList()
    ): KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(
        instanceId = instanceId,
        sourceThreadId = sourceThreadId,
        status = status,
        selectedTab = selectedTab,
        messages = messages,
        tasks = tasks,
        showReplyActions = showReplyActions,
        members = members,
        settingsActions = settingsActions
    )

    @Test
    fun membersOnlyChange_keepsSignatureEqual() {
        val a = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(KuiklyThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"))
        )
        val b = payload(
            messages = listOf(message("m1", replyCount = 1)),
            members = listOf(
                KuiklyThreadMemberItem(id = "u1", displayName = "User 1", kind = "human"),
                KuiklyThreadMemberItem(id = "u2", displayName = "User 2", kind = "human")
            )
        )
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun settingsActionsChange_keepsSignatureEqual() {
        val a = payload(
            messages = listOf(message("m1")),
            settingsActions = emptyList()
        )
        val b = payload(
            messages = listOf(message("m1")),
            settingsActions = listOf(
                KuiklyThreadSettingsAction(id = "leave", title = "Leave"),
                KuiklyThreadSettingsAction(id = "archive", title = "Archive")
            )
        )
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun statusChange_changesSignature() {
        val a = payload(status = "content")
        val b = payload(status = "error")
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun selectedTabChange_changesSignature() {
        val a = payload(selectedTab = "chat")
        val b = payload(selectedTab = "files")
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun appendingMessage_changesSignature() {
        val a = payload(messages = listOf(message("m1"), message("m2", replyCount = 2)))
        val b = payload(messages = listOf(message("m1"), message("m2", replyCount = 2), message("m3")))
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun equivalentMessagesListReferenceButSameContent_keepsSignatureEqual() {
        val sharedContent = listOf(
            message("m1", replyCount = 1, unreadReplyCount = 0),
            message("m2", replyCount = 0)
        )
        val a = payload(messages = sharedContent)
        // Different list reference (a new ArrayList copy), identical content.
        val b = payload(messages = sharedContent.toList())
        val aRef: List<KuiklyThreadMessageItem> = sharedContent
        val bRef: List<KuiklyThreadMessageItem> = b.messages
        assertNotEquals(System.identityHashCode(aRef), System.identityHashCode(bRef))
        assertEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun firstReplyChange_changesSignature() {
        val a = payload(messages = listOf(message("m1", replyCount = 1, unreadReplyCount = 0)))
        val b = payload(
            messages = listOf(
                message("m1", replyCount = 1, unreadReplyCount = 0),
                message("m2", replyCount = 2, unreadReplyCount = 1)
            )
        )
        // First reply shifts from m1 (id-8/1/0) to m2 (id-8/2/1).
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun showReplyActionsChange_changesSignature() {
        val a = payload(showReplyActions = true)
        val b = payload(showReplyActions = false)
        assertNotEquals(a.toRenderSignature(), b.toRenderSignature())
    }

    @Test
    fun emptyMessages_haveZeroReplyRowsAndNullFirstReply() {
        val sig = payload().toRenderSignature()
        assertEquals(0, sig.replyRowsSize)
        assertEquals(null, sig.firstReplyId8)
        assertEquals(0, sig.firstReplyCount)
        assertEquals(0, sig.firstReplyUnreadCount)
    }
}
