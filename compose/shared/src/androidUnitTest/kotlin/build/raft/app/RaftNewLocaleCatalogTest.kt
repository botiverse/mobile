package build.raft.app

import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.common_cancel
import build.raft.app.resources.desc
import build.raft.app.resources.resolve
import build.raft.app.thread.key
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.StringResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The later UI languages (the web's es, pt-br, ko, zh-tw and ru): every English key resolves in
 * each of them, from the language's own catalog when it has the key, and from English when it does
 * not, never from another region of the same language (zh-TW must not show zh-CN, pt-BR no other
 * Portuguese) and never by throwing. Placeholder parity per key is ComposeResourcesCatalogParityTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(InternalResourceApi::class)
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
class RaftNewLocaleCatalogTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private fun resolveIn(tag: String, resource: StringResource): String {
        StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
        return resource.desc().resolve()
    }

    @Test
    fun aKeyMissingFromANewCatalogShowsTheEnglish() {
        val source = Res.string.common_cancel
        mapOf("es" to "Cancelar", "pt-BR" to "Cancelar", "ko" to "취소", "zh-TW" to "取消", "ru" to "Отмена").forEach { (tag, translated) ->
            assertEquals(translated, resolveIn(tag, source), tag)
        }
        NEW_LOCALES.forEach { (tag, dir) ->
            // The same string without this language's catalog entry, as when a key is not yet translated.
            val untranslated = StringResource(source.id, source.key, source.items.filterNot { "/$dir/" in it.path }.toSet())
            assertTrue(untranslated.items.size < source.items.size, "$dir has common_cancel")
            assertEquals("Cancel", resolveIn(tag, untranslated), "$tag without its catalog entry")
        }
    }

    private companion object {
        /** App locale tag ([build.raft.app.data.kmmResourceLocaleTag]) to its catalog folder. */
        val NEW_LOCALES = listOf("es" to "values-es", "pt-BR" to "values-pt-rBR", "ko" to "values-ko", "zh-TW" to "values-zh-rTW", "ru" to "values-ru")
    }
}
