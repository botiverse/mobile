package build.raft.app

import build.raft.app.data.SettingsDiagnosticsFeedbackDraft
import build.raft.app.data.SharedMembersLoadStatus
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadComposerLayoutTest {
    @Test
    fun threadComposerBottomPaddingIncludesClosedKeyboardSafeArea() {
        assertEquals(38.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingDoesNotDoubleCountSafeAreaWhenKeyboardOpen() {
        assertEquals(134.dp, threadComposerBottomPadding(keyboardInsetDp = 120f, bottomSafeArea = 24.dp))
    }

    @Test
    fun threadComposerBottomPaddingKeepsSmallLiftWithoutSafeArea() {
        assertEquals(14.dp, threadComposerBottomPadding(keyboardInsetDp = -1f, bottomSafeArea = 0.dp))
    }

    @Test
    fun mentionSuggestionInsertMovesCursorAfterInsertedMention() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin ", cursor = 12), edit)
    }

    @Test
    fun mentionSuggestionInsertPreservesSuffixWithoutDoubleSpace() {
        val edit = applyKuiklyComposerSuggestionEdit(
            text = "ping @ar world",
            selectionStart = 8,
            selectionEnd = 8,
            triggerStart = 5,
            replacement = "@artin "
        )

        assertEquals(KuiklyComposerSuggestionEdit(text = "ping @artin world", cursor = 12), edit)
    }

    @Test
    fun memberSuggestionTriggerShowsLoadingPanelBeforeItemsArrive() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberLoading,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.InFlight,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsItemsDuringRefreshWhenCandidatesExist() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberItems,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 1,
                memberStatus = SharedMembersLoadStatus.InFlight,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsEmptyPanelAfterLoadedEmptyResult() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "missing", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun memberSuggestionTriggerShowsEmptyWhenNoRequestIsInFlight() {
        assertEquals(
            ThreadComposerSuggestionPanelState.MemberEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Idle,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun channelSuggestionTriggerShowsItemsWhenFilteredChannelsExist() {
        assertEquals(
            ThreadComposerSuggestionPanelState.ChannelItems,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "home", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 1,
                channelStatus = SharedMembersLoadStatus.InFlight
            )
        )
    }

    @Test
    fun channelSuggestionTriggerShowsEmptyWhenNoRequestIsInFlight() {
        assertEquals(
            ThreadComposerSuggestionPanelState.ChannelEmpty,
            threadComposerSuggestionPanelState(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "", triggerStart = 0),
                memberCount = 0,
                memberStatus = SharedMembersLoadStatus.Loaded,
                channelCount = 0,
                channelStatus = SharedMembersLoadStatus.Idle
            )
        )
    }

    @Test
    fun renderedSuggestionsKeepRetainedCandidatesWhileRefreshIsInFlight() {
        assertEquals(
            listOf("@artin"),
            threadComposerRenderedSuggestions(
                loadedSuggestions = emptyList(),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = emptyList(),
                status = SharedMembersLoadStatus.InFlight
            )
        )
    }

    @Test
    fun renderedSuggestionsReplaceRetainedCandidatesWhenRefreshCompletesEmpty() {
        assertEquals(
            emptyList(),
            threadComposerRenderedSuggestions(
                loadedSuggestions = emptyList(),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = listOf("@old"),
                status = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun renderedSuggestionsUseFreshCandidatesWhenRefreshReturnsItems() {
        assertEquals(
            listOf("@kmp"),
            threadComposerRenderedSuggestions(
                loadedSuggestions = listOf("@kmp"),
                retainedSuggestions = listOf("@artin"),
                fallbackSuggestions = emptyList(),
                status = SharedMembersLoadStatus.Loaded
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightReservesRoomForMultipleRows() {
        assertEquals(
            144.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 3,
                rowMinHeight = 38.dp
            )
        )
    }

    @Test
    fun suggestionPanelContentHeightCapsAtPanelMaxHeight() {
        assertEquals(
            240.dp,
            composerSuggestionPanelContentHeight(
                itemCount = 7,
                rowMinHeight = 38.dp
            )
        )
    }

    @Test
    fun suggestionRefreshRequestTracksOpenedMentionAndChannelPanels() {
        assertEquals(
            ThreadComposerSuggestionRefreshRequest.Member,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0)
            )
        )
        assertEquals(
            ThreadComposerSuggestionRefreshRequest.Channel,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.ChannelTrigger(query = "", triggerStart = 0)
            )
        )
    }

    @Test
    fun suggestionRefreshRequestSkipsClosedOrInactivePanels() {
        assertEquals(
            null,
            threadComposerSuggestionRefreshRequest(
                focused = false,
                trigger = KuiklyComposerSuggestionTrigger.MemberTrigger(query = "", triggerStart = 0)
            )
        )
        assertEquals(
            null,
            threadComposerSuggestionRefreshRequest(
                focused = true,
                trigger = KuiklyComposerSuggestionTrigger.None
            )
        )
    }

    @Test
    fun composerAttachmentRowsUseReactImageAndFileWidths() {
        val rows =
            composerAttachmentRows(
                listOf(
                    KuiklyPendingAttachmentItem(
                        id = "image",
                        fileName = "composer-preview.png",
                        mimeType = "image/png",
                        previewUri = "data:image/png;base64,"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "report",
                        fileName = "release-notes.pdf",
                        mimeType = "application/pdf"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "logs",
                        fileName = "android-smoke-log.zip",
                        mimeType = "application/zip"
                    ),
                    KuiklyPendingAttachmentItem(
                        id = "trace",
                        fileName = "oversized-trace.jsonl",
                        mimeType = "application/json"
                    )
                )
            )

        assertEquals(listOf(listOf("image", "report"), listOf("logs", "trace")), rows.map { row -> row.map { it.id } })
        assertEquals(64, rows.first().first().composerAttachmentPreviewWidthDp())
        assertEquals(128, rows.first()[1].composerAttachmentPreviewWidthDp())
    }

    @Test
    fun diagnosticsFeedbackComposerMessageDoesNotInlineDiagnosticsText() {
        val draft =
            SettingsDiagnosticsFeedbackDraft(
                message =
                    """
                    ## Diagnostics

                    App, device, server, and recent logs should stay in the zip attachment.
                    """.trimIndent(),
                fileName = "slock-feedback.zip",
                mimeType = "application/zip",
                sizeBytes = 12L,
                bytes = byteArrayOf(1, 2, 3)
            )

        assertEquals(
            "Diagnostics zip attached.\n\n<!-- Describe what happened here. -->",
            draft.diagnosticsComposerMessage()
        )
    }
}
