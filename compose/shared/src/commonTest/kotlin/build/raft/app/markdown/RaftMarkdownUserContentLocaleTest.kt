package build.raft.app.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import build.raft.app.RaftUserContentLocaleList
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMarkdownUserContentLocaleTest {
    @Test
    fun aStyleThatLostItsLocaleGetsTheUserContentOne() {
        // The vendor merge hands the provider a style without a locale.
        assertEquals(RaftUserContentLocaleList, TextStyle().raftMarkdownUserContentLocale().localeList)
    }

    @Test
    fun aLocaleSetOnPurposeStays() {
        val ja = LocaleList("ja")
        assertEquals(ja, TextStyle(localeList = ja).raftMarkdownUserContentLocale().localeList)
    }
}
