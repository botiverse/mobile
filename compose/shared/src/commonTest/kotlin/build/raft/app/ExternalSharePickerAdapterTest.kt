package build.raft.app

import build.raft.app.data.ExternalShareEnvelope
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareDelivery
import build.raft.app.data.ExternalShareDeliveryStatus
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalSharePickerAdapterTest {
    @Test
    fun multiSelectionProjectsThroughLandedPickerContract() {
        val first = target("channel-a")
        val second = target("channel-b")
        val selecting = state(targets = listOf(first, second), selected = listOf(first), generation = 7)

        val surface = selecting.toExternalSharePickerSurface()

        assertEquals("external-share:token", surface.picker.sessionKey.sourceContext)
        assertEquals(setOf(first.externalKey()), surface.picker.selectedKeys)
        assertEquals(7, surface.picker.requestGeneration)
        assertTrue(surface.picker.interactive)
    }

    @Test
    fun localDebouncedQueryKeepsTextButDisablesStaleRowsUntilStoreCatchesUp() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 7)

        val pending = selecting.toExternalSharePickerSurface(queryOverride = "search")

        assertEquals("search", pending.picker.query)
        assertEquals(SlockForwardPickerLoadState.Loading, pending.picker.loadState)
        assertFalse(pending.picker.interactive)
    }

    @Test
    fun staleOrTamperedRowTapFailsClosed() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 9)
        val rendered = first.toSlockForwardTargetPickerItem()

        assertNull(
            selecting.toExternalShareIntent(
                SlockForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 8)
            )
        )
        assertNull(
            selecting.toExternalShareIntent(
                SlockForwardTargetPickerIntent.TargetSelected(
                    rendered.copy(payload = target("channel-other")),
                    renderGeneration = 9
                )
            )
        )
        assertIs<ExternalShareIntent.ToggleTarget>(
            selecting.toExternalShareIntent(
                SlockForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 9)
            )
        )
    }

    @Test
    fun continueAndRemoveUseTypedPickerIntents() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), selected = listOf(first), generation = 3)

        assertEquals(ExternalShareIntent.Continue, selecting.toExternalShareIntent(SlockForwardTargetPickerIntent.Continue))
        val remove =
            assertIs<ExternalShareIntent.ToggleTarget>(
                selecting.toExternalShareIntent(SlockForwardTargetPickerIntent.RemoveSelection(first.externalKey()))
            )
        assertEquals(first, remove.target)
    }

    @Test
    fun confirmationProjectionKeepsRecipientsContentAndComment() {
        val first = target("channel-a")
        val confirming =
            ExternalShareState.Confirming(
                envelope =
                    ExternalShareEnvelope(
                        "token",
                        1L,
                        "android",
                        text = "Body",
                        subject = "Subject"
                    ),
                serverId = "server",
                selectedTargets = listOf(first),
                comment = "Comment"
            )

        val projection = confirming.toExternalShareConfirmationUiState()

        assertEquals(listOf(first.externalKey()), projection.selectedTargets.map { it.stableKey })
        assertEquals(listOf("Subject", "Body"), projection.contentLines)
        assertEquals("Comment", projection.comment)
        assertTrue(projection.canSend)
    }

    @Test
    fun singleSuccessfulDeliveryNavigatesToTypedConversation() {
        val destination = target("channel-a")
        val completed =
            ExternalShareState.Completed(
                envelope = ExternalShareEnvelope("token", 1L, "android", text = "hello"),
                serverId = "server",
                comment = "",
                attemptId = 4L,
                deliveries =
                    listOf(
                        ExternalShareDelivery(
                            target = destination,
                            deliveryId = "delivery",
                            status = ExternalShareDeliveryStatus.Sent
                        )
                    )
            )

        assertEquals(destination, completed.singleSuccessfulNavigationTargetOrNull())
        val identity = assertNotNull(destination.toExternalShareConversationIdentityOrNull())
        assertEquals(ChannelId("channel-a"), identity.channelId)
        assertEquals(destination.channelKind, identity.kind)
    }

    @Test
    fun partialOrMultipleDeliveryDoesNotGuessNavigationTarget() {
        val first = target("channel-a")
        val second = target("channel-b")
        val envelope = ExternalShareEnvelope("token", 1L, "android", text = "hello")
        val partial =
            ExternalShareState.Completed(
                envelope,
                "server",
                "",
                5L,
                listOf(
                    ExternalShareDelivery(first, "first", ExternalShareDeliveryStatus.Sent),
                    ExternalShareDelivery(second, "second", ExternalShareDeliveryStatus.Failed, "failed")
                )
            )
        val multiple =
            partial.copy(
                deliveries =
                    listOf(
                        ExternalShareDelivery(first, "first", ExternalShareDeliveryStatus.Sent),
                        ExternalShareDelivery(second, "second", ExternalShareDeliveryStatus.Sent)
                    )
            )

        assertNull(partial.singleSuccessfulNavigationTargetOrNull())
        assertNull(multiple.singleSuccessfulNavigationTargetOrNull())
    }

    private fun state(
        targets: List<ForwardMessageTarget>,
        selected: List<ForwardMessageTarget> = emptyList(),
        generation: Int
    ): ExternalShareState.SelectingTargets =
        ExternalShareState.SelectingTargets(
            envelope = ExternalShareEnvelope("token", 1L, "android", text = "hello"),
            serverId = "server",
            targets = targets,
            selectedTargets = selected,
            requestGeneration = generation
        )

    private fun target(id: String) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId(id)
        )

    private fun ForwardMessageTarget.externalKey(): String = "${serverId.value}:${kind.name}:$id"
}
