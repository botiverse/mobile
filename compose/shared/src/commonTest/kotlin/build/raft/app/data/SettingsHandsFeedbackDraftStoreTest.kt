package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHandsFeedbackDraftStoreTest {
    @Test
    fun textAndCachedAttachmentsSurviveStoreRecreationForSameAccount() {
        val preferences = InMemoryRaftKeyValueStore()
        SettingsHandsFeedbackDraftStore(preferences).save(
            accountId = "user-1",
            text = "Repro steps\nExpected result",
            attachments =
                listOf(
                    PlatformFile(
                        id = "image-1",
                        fileName = "screen.png",
                        mimeType = "image/png",
                        sizeBytes = 2048,
                        previewUri = "file:///cache/screen.png",
                        fileRef = "file:///cache/screen.png",
                        source = "platform_picker"
                    )
                )
        )

        val restored = SettingsHandsFeedbackDraftStore(preferences).read("user-1")

        assertEquals("Repro steps\nExpected result", restored.text)
        assertEquals(1, restored.attachments.size)
        assertEquals("screen.png", restored.attachments.single().fileName)
        assertEquals("file:///cache/screen.png", restored.attachments.single().fileRef)
    }

    @Test
    fun draftsAreAccountScopedAndBlankContentClearsPersistedValue() {
        val preferences = InMemoryRaftKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        store.save("user-1", "First account draft")
        store.save("user-2", "Second account draft")

        assertEquals("First account draft", store.read("user-1").text)
        assertEquals("Second account draft", store.read("user-2").text)

        store.save("user-1", "   ")

        assertEquals(SettingsHandsFeedbackDraftSnapshot(), store.read("user-1"))
        assertEquals("Second account draft", store.read("user-2").text)
    }

    @Test
    fun transientByteOnlyAttachmentsAreNotRestoredAsBrokenFiles() {
        val preferences = InMemoryRaftKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        store.save(
            accountId = "user-1",
            text = "Keep the text",
            attachments =
                listOf(
                    PlatformFile(
                        id = "memory-only",
                        fileName = "screen.png",
                        mimeType = "image/png",
                        bytes = byteArrayOf(1, 2, 3)
                    )
                )
        )

        val restored = store.read("user-1")

        assertEquals("Keep the text", restored.text)
        assertEquals(emptyList(), restored.attachments)
    }

    @Test
    fun successfulSubmissionClearsTextAndAttachments() {
        val preferences = InMemoryRaftKeyValueStore()
        val store = SettingsHandsFeedbackDraftStore(preferences)
        store.save(
            accountId = "user-1",
            text = "Submitted feedback",
            attachments =
                listOf(
                    PlatformFile(
                        id = "image-1",
                        fileName = "screen.png",
                        fileRef = "file:///cache/screen.png"
                    )
                )
        )

        store.clear("user-1")

        assertEquals(SettingsHandsFeedbackDraftSnapshot(), store.read("user-1"))
    }
}
