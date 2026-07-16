package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelNameValidationTest {
    @Test
    fun allowsServerToOwnUnicodeCategoryValidation() {
        assertTrue("general".isSubmittableChannelName())
        assertTrue("markdown专修".isSubmittableChannelName())
        assertTrue("𐐀room".isSubmittableChannelName())
        assertTrue("A²".isSubmittableChannelName())
        assertTrue("AⅧ".isSubmittableChannelName())
        assertTrue("Á".isSubmittableChannelName())
        assertTrue("🚀release".isSubmittableChannelName())
        assertTrue("release notes".isSubmittableChannelName())
    }

    @Test
    fun enforcesOnlyPortableLocalSubmissionBounds() {
        assertFalse("".isSubmittableChannelName())
        assertFalse("   ".isSubmittableChannelName())
        assertTrue("a".repeat(ChannelNameMaxLength).isSubmittableChannelName())
        assertFalse("a".repeat(ChannelNameMaxLength + 1).isSubmittableChannelName())
        assertTrue(("𐐀" + "a".repeat(ChannelNameMaxLength - 2)).isSubmittableChannelName())
        assertFalse(("𐐀" + "a".repeat(ChannelNameMaxLength - 1)).isSubmittableChannelName())
    }
}
