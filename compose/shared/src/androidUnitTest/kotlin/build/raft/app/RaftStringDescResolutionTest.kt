package build.raft.app

import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.common_cancel
import build.raft.app.resources.database_recovery_detail
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.resolve
import build.raft.app.resources.thread_action_card_install_dialog_description
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Strings resolve from the packaged Compose resources in the app language ([StringDesc.localeType]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftStringDescResolutionTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    @Test
    fun resolvesTheCatalogOfTheAppLanguage() {
        mapOf("en" to "Cancel", "ja" to "キャンセル", "zh-CN" to "取消").forEach { (tag, expected) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(expected, Res.string.common_cancel.desc().resolve(), "locale $tag")
        }
    }

    @Test
    fun formatsPositionalArguments() {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val text = Res.string.database_recovery_detail.format(3, 5).resolve()
        assertTrue(text.startsWith("This app supports database version 5, but your local data uses version 3."), text)
    }

    @Test
    fun androidApostropheEscapesAreDecoded() {
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
        val text = Res.string.thread_action_card_install_dialog_description.format("bot", "GitHub").resolve()
        assertTrue("isn't installed" in text, text)
        assertFalse('\\' in text, text)
    }

    /** A feedback card's 429 `feedback_rate_limited` (botiverse/slock 7799cc436) reads in the app language, not the server's English. */
    @Test
    fun aFeedbackRateLimitReadsInTheAppLanguage() {
        val expected =
            mapOf(
                "en" to listOf(
                    "Feedback is being sent too frequently. What you entered is kept; try again in 30 minutes.",
                    "Feedback is being sent too frequently. What you entered is kept; try again in 1 minute.",
                    "Feedback is being sent too frequently. What you entered is kept; try again later.",
                ),
                "zh-CN" to listOf(
                    "反馈提交过于频繁。已填写的内容已保留，请在 30 分钟后重试。",
                    "反馈提交过于频繁。已填写的内容已保留，请在 1 分钟后重试。",
                    "反馈提交过于频繁。已填写的内容已保留，请稍后重试。",
                ),
                "ja" to listOf(
                    "フィードバックの送信回数が多すぎます。入力内容は保持されています。30 分後にもう一度お試しください。",
                    "フィードバックの送信回数が多すぎます。入力内容は保持されています。1 分後にもう一度お試しください。",
                    "フィードバックの送信回数が多すぎます。入力内容は保持されています。しばらくしてからもう一度お試しください。",
                ),
            )
        expected.forEach { (tag, texts) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            val actual =
                listOf(1800L, 45L, null).map { wait ->
                    threadActionCardSpecErrorDesc(ThreadActionCardSpecError.FeedbackRateLimited(wait))?.resolve()
                }
            assertEquals(texts, actual, "locale $tag")
        }
    }
}
