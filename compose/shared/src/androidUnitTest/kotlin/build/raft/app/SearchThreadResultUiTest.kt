package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.InboxItem
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.resources.StringDesc
import build.raft.app.resources.resolve
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A thread result on the search page reads as the web's (botiverse/slock staging
 * `SearchMessageResultsSection`, `search.hitsCount`): "1 hit" / "2 hits" in each language's plural
 * forms, and a title that never marks the query, only its hits do.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchThreadResultUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun aThreadCountsItsHitsInTheLanguagesPluralForms() {
        assertEquals(listOf("1 hit", "2 hits"), listOf(1, 2).map { homeSearchThreadHitsCount(it).resolve() })
        // Russian one / few / many (files/plurals/ru.json, the web's ru `search.hitsCount`).
        StringDesc.localeType = StringDesc.LocaleType.Custom("ru")
        assertEquals(
            listOf("1 совпадение", "3 совпадения", "5 совпадений"),
            listOf(1, 3, 5).map { homeSearchThreadHitsCount(it).resolve() },
        )
    }

    @Test
    fun theTitleNeverMarksTheQueryButTheHitsDo() =
        runRobolectricComposeUiTest {
            setContent {
                Host {
                    SharedHomeThreadResultGroup(
                        group = HomeSearchResultGroup.Thread(key = "thread:design", title = "Android release checklist", items = listOf(Hit)),
                        query = "android",
                        onOpenItem = {},
                    )
                }
            }

            val title = onNodeWithText("Android release checklist").fetchSemanticsNode().config[SemanticsProperties.Text]
            assertTrue(title.all { it.spanStyles.isEmpty() }, "the thread title has no hit mark: $title")
            val hit = onNodeWithText(HitText).fetchSemanticsNode().config[SemanticsProperties.Text]
            assertTrue(hit.any { it.spanStyles.isNotEmpty() }, "the hit marks the query: $hit")
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) { content() }
        }
    }

    private companion object {
        const val HitText = "Review the Android composer crop before release."

        val Hit =
            InboxItem(
                key = "thread:design:hit-1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-design"),
                channelName = "design",
                lastMessageId = MessageId("hit-1"),
                lastMessagePreview = HitText,
                lastMessageAt = "2026-10-23T16:31:00Z",
                lastMessageSenderName = "artin",
                lastMessageSenderKind = MemberKind.Human,
                threadChannelId = ChannelId("thread-design"),
                parentChannelId = ChannelId("ch-design"),
                parentChannelName = "design",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-1"),
                parentMessagePreview = "Android release checklist",
            )
    }
}
