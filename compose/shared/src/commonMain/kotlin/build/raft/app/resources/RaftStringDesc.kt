@file:OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)

package build.raft.app.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getString

/*
 * The app's string layer on top of Compose Multiplatform resources (`Res.string.<key>`,
 * composeResources/values*, generated into this package).
 *
 * It keeps the contract the former KuiklyBase kmmresource (moko) runtime gave the app:
 * - the UI language is the app's own choice ([StringDesc.localeType], set from the persisted
 *   `AppLocale` by `rememberKmmResourceLocaleSeed`), not only the system one, for composables
 *   ([stringResource], [localized]) and for code outside composition ([resolve]) alike;
 * - [StringDesc] values (resource, formatted resource, raw text) can be built anywhere and
 *   resolved later.
 *
 * Use [stringResource] from this package, not `org.jetbrains.compose.resources.stringResource`:
 * the official one follows the system locale only.
 */

/** A string to be resolved later in the app language (the former kmmresource `StringDesc`). */
sealed interface StringDesc {
    /** Which language strings resolve in (the former kmmresource `StringDesc.LocaleType`). */
    sealed class LocaleType {
        /** The system language, matched against the shipped catalogs. */
        data object System : LocaleType()

        /** An explicit BCP 47 tag (`en`, `ja`, `zh-CN`). */
        data class Custom(val localeTag: String) : LocaleType()
    }

    companion object {
        private val localeTypeState = mutableStateOf<LocaleType>(LocaleType.System)

        /**
         * Process-wide app language for string lookups. Compose snapshot state: every composable
         * that resolved a string with it recomposes when it changes (equal writes are no-ops).
         */
        var localeType: LocaleType
            get() = localeTypeState.value
            set(value) {
                localeTypeState.value = value
            }
    }
}

data class ResourceStringDesc(val stringRes: StringResource) : StringDesc

data class ResourceFormattedStringDesc(val stringRes: StringResource, val args: List<Any>) : StringDesc

data class RawStringDesc(val string: String) : StringDesc

@Suppress("FunctionName")
fun StringDesc.Companion.Resource(stringRes: StringResource): ResourceStringDesc = ResourceStringDesc(stringRes)

@Suppress("FunctionName")
fun StringDesc.Companion.ResourceFormatted(stringRes: StringResource, args: List<Any>): ResourceFormattedStringDesc =
    ResourceFormattedStringDesc(stringRes, args)

@Suppress("FunctionName")
fun StringDesc.Companion.ResourceFormatted(stringRes: StringResource, vararg args: Any): ResourceFormattedStringDesc =
    ResourceFormattedStringDesc(stringRes, args.toList())

@Suppress("FunctionName")
fun StringDesc.Companion.Raw(string: String): RawStringDesc = RawStringDesc(string)

fun String.desc(): RawStringDesc = RawStringDesc(this)

fun StringResource.desc(): ResourceStringDesc = ResourceStringDesc(this)

fun StringResource.format(args: List<Any>): ResourceFormattedStringDesc = ResourceFormattedStringDesc(this, args)

fun StringResource.format(vararg args: Any): ResourceFormattedStringDesc = ResourceFormattedStringDesc(this, args.toList())

/** [this] in the app language, outside composition (host strings, toasts, logs shown to users). */
fun StringDesc.resolve(): String = resolveIn(raftResourceEnvironment(StringDesc.localeType, raftSystemPreferredLanguageTagsOrEmpty()))

/** [this] in the app language; recomposes when the app language changes. */
@Composable
fun StringDesc.localized(): String {
    val environment = rememberRaftResourceEnvironment()
    return remember(this, environment) { resolveIn(environment) }
}

/** The string [resource] in the app language. */
@Composable
fun stringResource(resource: StringResource): String {
    val environment = rememberRaftResourceEnvironment()
    return remember(resource, environment) { ResourceStringDesc(resource).resolveIn(environment) }
}

/** The string [resource] in the app language, `%N$s` / `%N$d` replaced by [args]. */
@Composable
fun stringResource(resource: StringResource, vararg args: Any): String {
    val environment = rememberRaftResourceEnvironment()
    val argList = args.toList()
    return remember(resource, argList, environment) { ResourceFormattedStringDesc(resource, argList).resolveIn(environment) }
}

/**
 * The lookup environment for the current [StringDesc.localeType]. The read subscribes the caller
 * (snapshot state), and the environment is keyed by the value itself, so a language switch always
 * recomposes into the new catalog. Locale.current keys system language changes; the lookup walks
 * the whole preferred-language list.
 */
@Composable
private fun rememberRaftResourceEnvironment(): ResourceEnvironment {
    val localeType = StringDesc.localeType
    val systemLocale = Locale.current
    return remember(localeType, systemLocale) {
        raftResourceEnvironment(localeType, raftSystemPreferredLanguageTagsOrEmpty())
    }
}

/**
 * Compose resources reads string catalogs from disk asynchronously; the app resolves strings
 * synchronously (composition and host callbacks), as Compose's own `stringResource` does on
 * Android and iOS. Catalogs are cached after the first read.
 */
private fun StringDesc.resolveIn(environment: ResourceEnvironment): String =
    when (this) {
        is RawStringDesc -> string
        is ResourceStringDesc -> runBlocking { getString(environment, stringRes) }
        is ResourceFormattedStringDesc -> {
            // kmmresource resolved StringDesc arguments before formatting; keep that.
            val resolvedArgs = args.map { arg -> (arg as? StringDesc)?.resolveIn(environment) ?: arg }
            runBlocking { getString(environment, stringRes, *resolvedArgs.toTypedArray()) }
        }
    }

/** Language and region of the catalog [localeType] selects ("" = no region). */
internal data class RaftResourceLocale(val language: String, val region: String)

/**
 * The system's preferred languages, most preferred first, as BCP 47 tags (Android: the locale
 * list; iOS: `NSLocale.preferredLanguages`).
 */
internal expect fun raftSystemPreferredLanguageTags(): List<String>

/** [raftSystemPreferredLanguageTags], or empty where the platform cannot tell (unit-test stubs). */
internal fun raftSystemPreferredLanguageTagsOrEmpty(): List<String> =
    runCatching { raftSystemPreferredLanguageTags() }.getOrDefault(emptyList())

/**
 * The catalog the system language selects: the one source for UI strings, the UI text locale
 * (Han glyph forms) and the display language tag, so the three always agree.
 */
internal fun raftSystemResourceLocale(
    systemLanguageTags: List<String> = raftSystemPreferredLanguageTagsOrEmpty(),
): RaftResourceLocale = raftResourceLocale(StringDesc.LocaleType.System, systemLanguageTags)

/**
 * Catalog selection, matching what the platform resource lookups did for kmmresource: an
 * explicit tag picks its catalog; for the system language the first preferred language with a
 * shipped catalog wins (Japanese; Simplified Chinese, i.e. Chinese that is not Traditional by
 * Hant script or a TW/HK/MO region without a script; English). Unsupported languages are
 * skipped; with no match the English base catalog applies.
 */
internal fun raftResourceLocale(
    localeType: StringDesc.LocaleType,
    systemLanguageTags: List<String>,
): RaftResourceLocale =
    when (localeType) {
        is StringDesc.LocaleType.Custom -> {
            val parts = raftLanguageTagParts(localeType.localeTag)
            RaftResourceLocale(
                language = parts.firstOrNull()?.lowercase().orEmpty(),
                region = parts.drop(1).firstOrNull { it.length == 2 }?.uppercase().orEmpty(),
            )
        }
        StringDesc.LocaleType.System ->
            systemLanguageTags.firstNotNullOfOrNull(::raftSupportedResourceLocale) ?: RaftResourceLocale("en", "")
    }

private fun raftLanguageTagParts(tag: String): List<String> =
    tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }

/** The shipped catalog for one preferred-language [tag], or null when there is none. */
private fun raftSupportedResourceLocale(tag: String): RaftResourceLocale? {
    val parts = raftLanguageTagParts(tag)
    val language = parts.firstOrNull()?.lowercase() ?: return null
    val subtags = parts.drop(1)
    val script = subtags.firstOrNull { it.length == 4 && it.all(Char::isLetter) }.orEmpty()
    val region = subtags.firstOrNull { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }
        ?.uppercase().orEmpty()
    return when (language) {
        "ja" -> RaftResourceLocale("ja", "")
        "en" -> RaftResourceLocale("en", "")
        "zh" -> {
            val traditional =
                script.equals("Hant", ignoreCase = true) || (script.isEmpty() && region in setOf("TW", "HK", "MO"))
            if (traditional) null else RaftResourceLocale("zh", "CN")
        }
        else -> null
    }
}

/**
 * Compose resources picks a catalog from a [ResourceEnvironment] but only builds one for the
 * system locale (`getSystemResourceEnvironment`); its constructor is library-internal. The app
 * language is an in-app setting that must not change the process locale (that would also change
 * date and number formatting and CJK glyph selection), so the environment is built directly.
 * A removed or changed constructor fails the compile or RaftStringDescResolutionTest.
 * TODO(compose-resources): switch to a public API once Compose resources offers a locale override.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private fun raftResourceEnvironment(localeType: StringDesc.LocaleType, systemLanguageTags: List<String>): ResourceEnvironment {
    val locale = raftResourceLocale(localeType, systemLanguageTags)
    // Strings carry no theme or density qualifiers; these two only satisfy the constructor.
    return ResourceEnvironment(
        LanguageQualifier(locale.language),
        ScriptQualifier(""),
        RegionQualifier(locale.region),
        ThemeQualifier.LIGHT,
        DensityQualifier.MDPI,
    )
}
