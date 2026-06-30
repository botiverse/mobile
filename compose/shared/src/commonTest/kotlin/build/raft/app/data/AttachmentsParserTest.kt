package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentsParserTest {
    @Test
    fun channelFilesRejectNestedAttachmentShape() {
        val snapshot =
            parseSharedChannelFilesSnapshot(
                bodyText =
                    """
                    {
                      "files": [
                        {
                          "attachment": {
                            "id": "file-1",
                            "filename": "a.png",
                            "mimeType": "image/png",
                            "sizeBytes": 128
                          },
                          "messageId": "message-1"
                        }
                      ],
                      "nextCursor": null
                    }
                    """.trimIndent(),
                channelId = ChannelId("channel-1")
            )

        assertTrue(snapshot.items.isEmpty())
        assertEquals(false, snapshot.hasMore)
    }
}
