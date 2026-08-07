package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
