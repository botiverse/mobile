package build.raft.designsystem

import build.raft.designsystem.components.RaftAppLinkCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageEmbedDefaults
import build.raft.designsystem.components.RaftMessageForwardedBundleDefaults
import build.raft.designsystem.components.RaftMessageRepliesDefaults
import build.raft.designsystem.components.RaftPreviewCardDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

/** raft-ui 0.84.0 cards in messages: one frame, two widths, one inset, so the cards never drift apart. */
class RaftMessageCardWidthsTest {
    @Test
    fun anItemCardIs400dpLikeTheItemPopover() {
        assertEquals(400f, RaftMessageEmbedDefaults.ItemMaxWidth.value)
        assertEquals(RaftMessageEmbedDefaults.ItemMaxWidth, RaftPreviewCardDefaults.ItemWidth)
        assertEquals(RaftMessageEmbedDefaults.ItemMaxWidth, RaftAppLinkCardDefaults.MaxWidth)
        assertEquals(RaftMessageEmbedDefaults.ItemMaxWidth, RaftMessageActionCardDefaults.MaxWidth)
        assertEquals(RaftMessageEmbedDefaults.ItemMaxWidth, RaftMessageEmbedDefaults.MaxWidth, "the Artifact card")
    }

    @Test
    fun aCardOfMessagesIs560dp() {
        assertEquals(560f, RaftMessageEmbedDefaults.ContentMaxWidth.value)
        assertEquals(RaftMessageEmbedDefaults.ContentMaxWidth, RaftMessageForwardedBundleDefaults.MaxWidth)
        assertEquals(RaftMessageEmbedDefaults.ContentMaxWidth, RaftMessageRepliesDefaults.MaxWidth)
    }

    @Test
    fun everyCardTakesThe12dpInset() {
        assertEquals(12f, RaftMessageEmbedDefaults.Inset.value)
    }
}
