package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The artifact viewer's save toast: by the host result's status words, never the host's English `message`. */
class RaftArtifactViewerSavedToastTest {
    private fun toast(result: String?): String =
        raftArtifactViewerSavedToast(
            result?.let { JSONObject(it) },
            saved = "saved",
            savedToDownloads = "savedToDownloads",
            alreadySaved = "alreadySaved",
            savedToFiles = "savedToFiles",
        )

    @Test
    fun androidSaysWhetherTheFileWentToDownloadsNowOrWasThereAlready() {
        assertEquals("savedToDownloads", toast("""{"status":"saved","message":"","cached":true,"fileName":"a.zip"}"""))
        assertEquals("alreadySaved", toast("""{"status":"saved","alreadySaved":true,"cached":true,"fileName":"a.zip"}"""))
    }

    @Test
    fun anyOtherSaveSaysSavedNotTheHostsWords() {
        // An older iOS host without `savedTo` answers in English only; desktop answers with no flags.
        assertEquals("saved", toast("""{"status":"saved","message":"Saved to Files","cached":false}"""))
        assertEquals("saved", toast("""{"status":"saved","uri":"file:/Users/a/Downloads/a.zip"}"""))
        assertEquals("saved", toast(null))
    }
}
