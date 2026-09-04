package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThreadComposerMountReadinessTest {
    @Test
    fun editorMountWaitsForExactRestoredDraftSession() {
        val expectedKey = "send=dm-1|parent=_|mention=dm-1"

        assertFalse(
            threadComposerEditorReadyToMount(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState = SharedThreadComposerDraftState(),
            )
        )
        assertFalse(
            threadComposerEditorReadyToMount(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState =
                    SharedThreadComposerDraftState(
                        instanceId = "composer-dm-1",
                        draftKey = "send=other|parent=_|mention=other",
                        seedText = "restored draft",
                    ),
            )
        )
        assertTrue(
            threadComposerEditorReadyToMount(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState =
                    SharedThreadComposerDraftState(
                        instanceId = "composer-dm-1",
                        draftKey = expectedKey,
                        seedText = "restored draft",
                    ),
            )
        )
    }

    @Test
    fun composerStartIdentityChangesForParentRebindButNotDraftEdits() {
        val composer = KuiklyThreadComposerPayload(instanceId = "composer-route")
        val firstSurface =
            ThreadComposerSurface(
                kind = KuiklyThreadSurfaceKind.ReplyThread,
                channelId = "thread-a",
                streamChannelId = "thread-a",
                parentChannelId = "channel-a",
                parentMessageId = "message-a"
            )
        val secondSurface = firstSurface.copy(parentMessageId = "message-b")

        val first = threadComposerStartIdentity(composer, firstSurface)
        val second = threadComposerStartIdentity(composer, secondSurface)

        assertNotEquals(first, second)
        assertNotEquals(first.draftKey, second.draftKey)
        assertEquals("composer-route", second.instanceId)
        assertEquals(
            first,
            threadComposerStartIdentity(
                composer.copy(seedText = "draft changed"),
                firstSurface
            )
        )
    }

}
