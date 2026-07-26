package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeI18nDisplayBoundaryTest {
    @Test
    fun membersCanonicalDefaultsLocalizeWithoutChangingCustomPayloadCopy() {
        assertEquals("成员", canonicalMembersDisplayText("Members", "Members", "成员"))
        assertEquals("Agent", canonicalMembersDisplayText("AGENTS", "AGENTS", "Agent"))
        assertEquals("HUMANS — ONLINE", canonicalMembersDisplayText("HUMANS — ONLINE", "HUMANS", "用户"))
        assertEquals("Workspace People", canonicalMembersDisplayText("Workspace People", "Members", "成员"))
    }

    @Test
    fun threadCanonicalFallbacksLocalizeWithoutChangingRealTitles() {
        assertEquals(
            "频道",
            localizedThreadHeaderTitle(
                "Channel",
                KuiklyThreadSurfaceKind.Channel,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "私聊",
            localizedThreadHeaderTitle(
                "Direct Message",
                KuiklyThreadSurfaceKind.DirectMessage,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "话题",
            localizedThreadHeaderTitle(
                ThreadReplyPageTitle,
                KuiklyThreadSurfaceKind.ReplyThread,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "product",
            localizedThreadHeaderTitle(
                "product",
                KuiklyThreadSurfaceKind.Channel,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "Ada",
            localizedThreadHeaderTitle(
                "Ada",
                KuiklyThreadSurfaceKind.DirectMessage,
                "话题",
                "频道",
                "私聊"
            )
        )
    }
}
