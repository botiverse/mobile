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
        assertFalse(
            threadComposerEditorReadyToMount(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState =
                    SharedThreadComposerDraftState(
                        instanceId = "composer-dm-1",
                        draftKey = expectedKey,
                        seedText = "restored draft",
                    ),
                pageInMotion = true,
            )
        )
    }

    /**
     * 1.28: while the page slides in, a loaded draft draws the composer at rest instead of an
     * empty strip; the editor itself still waits for the slide (#5320).
     */
    @Test
    fun aLoadedDraftWaitsOutTheSlideWithoutTheEditor() {
        val expectedKey = "send=dm-1|parent=_|mention=dm-1"
        val loaded = SharedThreadComposerDraftState(instanceId = "composer-dm-1", draftKey = expectedKey)

        assertTrue(threadComposerDraftLoaded(composerInstanceId = "composer-dm-1", expectedDraftKey = expectedKey, draftState = loaded))
        assertFalse(
            threadComposerEditorReadyToMount(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState = loaded,
                pageInMotion = true,
            )
        )
        assertFalse(
            threadComposerDraftLoaded(
                composerInstanceId = "composer-dm-1",
                expectedDraftKey = expectedKey,
                draftState = SharedThreadComposerDraftState(),
            ),
            "a draft not loaded yet keeps the blank slot",
        )
    }

    @Test
    fun onlyAnEmptyIdleComposerIsDrawnAtRestWhileThePageSlidesIn() {
        assertTrue(ThreadComposerUiState().isThreadComposerAtRestPicture())
        // As Task and a composer that cannot send yet are drawn as they are.
        assertTrue(ThreadComposerUiState(seedAsTask = true, enabled = false).isThreadComposerAtRestPicture())
        listOf(
            ThreadComposerUiState(seedText = "draft"),
            // Whitespace hides the editor's placeholder.
            ThreadComposerUiState(seedText = "  "),
            ThreadComposerUiState(mentionActionNotice = "Invited @ghost"),
            ThreadComposerUiState(composerMode = "sending"),
            ThreadComposerUiState(composerMode = "uploading"),
            ThreadComposerUiState(statusLabel = "Sending"),
            ThreadComposerUiState(unresolvedMentionHandles = listOf("@ghost")),
        ).forEach { state ->
            assertFalse(state.isThreadComposerAtRestPicture(), "drawn at rest: $state")
        }
    }
}
