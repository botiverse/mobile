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
}
