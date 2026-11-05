package build.raft.app

import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.allStringResources
import build.raft.app.resources.common_cancel
import build.raft.app.resources.desc
import build.raft.app.resources.resolve
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
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
    fun everyEnglishKeyResolvesInEveryNewLocale() {
        val resources = Res.allStringResources
        val english = parse("values")
        assertEquals(english.keys, resources.keys, "Res.allStringResources is the values/ catalog")
        val failures =
            NEW_LOCALES.flatMap { (tag, dir) ->
                val catalog = parse(dir)
                resources.mapNotNull { (key, resource) ->
                    val text =
                        runCatching { resolveIn(tag, resource) }
                            .getOrElse { return@mapNotNull "$tag/$key threw ${it::class.simpleName}: ${it.message}" }
                    val englishText = resolveIn("en", resource)
                    when {
                        text.isEmpty() && englishText.isNotEmpty() -> "$tag/$key is empty"
                        key !in catalog && text != englishText -> "$tag/$key is missing from $dir but does not show the English"
                        key in catalog && catalog[key] != english[key] && text == englishText ->
                            "$tag/$key is in $dir but shows the English"
                        else -> null
                    }
                }
            }
        assertEquals(emptyList(), failures)
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

    private fun parse(dir: String): Map<String, String> {
        val file = resourcesRoot().resolve(dir).resolve("strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return listOf(
            workingDirectory.resolve("src/commonMain/composeResources"),
            workingDirectory.resolve("shared/src/commonMain/composeResources"),
            workingDirectory.resolve("compose/shared/src/commonMain/composeResources"),
        ).firstOrNull(Files::isDirectory) ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        /** App locale tag ([build.raft.app.data.kmmResourceLocaleTag]) to its catalog folder. */
        val NEW_LOCALES = listOf("es" to "values-es", "pt-BR" to "values-pt-rBR", "ko" to "values-ko", "zh-TW" to "values-zh-rTW", "ru" to "values-ru")
    }
}
