@file:OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)

package build.raft.app.resources

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
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
        private val localeTypeFlow = MutableStateFlow<LocaleType>(LocaleType.System)

        /**
         * Process-wide app language for string lookups. Backed by [MutableStateFlow] rather than
         * Compose snapshot state so background-thread string resolution ([resolve],
         * [raftCurrentUiLocaleTag]) never initializes a snapshot state object off the main thread.
         * Composables subscribe via [observeLocaleType].
         */
        var localeType: LocaleType
            get() = localeTypeFlow.value
            set(value) {
                localeTypeFlow.value = value
            }

        @Composable
        fun observeLocaleType(): LocaleType = localeTypeFlow.collectAsState().value
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
fun StringDesc.resolve(): String = resolveIn(raftResolvedResourceEnvironment(StringDesc.localeType, raftSystemPreferredLanguageTagsOrEmpty()))

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

private data class RaftResolvedResourceEnvironment(
    val environment: ResourceEnvironment,
    val language: String,
    val region: String,
)

private class RaftResolvedStringCacheKey(
    val language: String,
    val region: String,
    val stringRes: StringResource,
) {
    // Compare StringResource by reference identity: generated Res.string.* values are singletons,
    // while synthetic StringResource copies with trimmed catalog items (e.g. locale fallback tests)
    // share the same id/equals and must not hit the full catalog's cached entry.
    override fun equals(other: Any?): Boolean =
        other is RaftResolvedStringCacheKey &&
            language == other.language &&
            region == other.region &&
            stringRes === other.stringRes

    override fun hashCode(): Int {
        var result = language.hashCode()
        result = 31 * result + region.hashCode()
        result = 31 * result + stringRes.hashCode()
        return result
    }
}

private const val RaftResolvedStringCacheMaxEntries = 4096

/**
 * In-memory resolved-string cache for zero-arg [ResourceStringDesc] lookups.
 * Avoids spinning up `runBlocking` + `EventLoop` on every newly composed screen (200+ calls per
 * pushed conversation) once a string has been resolved for that `(language, region)`.
 */
private object RaftResolvedStringCache {
    private val lock = SynchronizedObject()
    private val entries = HashMap<RaftResolvedStringCacheKey, String>(256)

    fun get(key: RaftResolvedStringCacheKey): String? = synchronized(lock) { entries[key] }

    fun put(key: RaftResolvedStringCacheKey, value: String) {
        synchronized(lock) {
            if (entries.size >= RaftResolvedStringCacheMaxEntries && key !in entries) {
                entries.clear()
            }
            entries[key] = value
        }
    }
}

private val LocalRaftResourceEnvironment = compositionLocalOf<RaftResolvedResourceEnvironment?> { null }

/**
 * Observes [StringDesc.observeLocaleType] and [Locale.current] once at a stable ancestor and shares
 * the resolved [RaftResolvedResourceEnvironment] down to every [stringResource] / [StringDesc.localized]
 * call beneath it, avoiding hundreds of per-call `collectAsState` coroutines and `LocaleListCompat`
 * queries on every pushed screen.
 */
@Composable
fun RaftProvideResourceEnvironment(content: @Composable () -> Unit) {
    if (LocalRaftResourceEnvironment.current != null) {
        content()
    } else {
        val environment = rememberRaftResourceEnvironmentUncached()
        CompositionLocalProvider(LocalRaftResourceEnvironment provides environment, content = content)
    }
}

/**
 * The lookup environment for the current [StringDesc.localeType]. Reads the shared
 * [LocalRaftResourceEnvironment] when provided by an ancestor, falling back to direct observation
 * in standalone composables / unit tests so a language switch always recomposes into the new catalog.
 */
@Composable
private fun rememberRaftResourceEnvironment(): RaftResolvedResourceEnvironment =
    LocalRaftResourceEnvironment.current ?: rememberRaftResourceEnvironmentUncached()

@Composable
private fun rememberRaftResourceEnvironmentUncached(): RaftResolvedResourceEnvironment {
    val localeType = StringDesc.observeLocaleType()
    val systemLocale = Locale.current
    return remember(localeType, systemLocale) {
        raftResolvedResourceEnvironment(localeType, raftSystemPreferredLanguageTagsOrEmpty())
    }
}

/**
 * Compose resources reads string catalogs from disk asynchronously; the app resolves strings
 * synchronously (composition and host callbacks), as Compose's own `stringResource` does on
 * Android and iOS. Catalogs are cached after the first read; [RaftResolvedStringCache] keeps
 * subsequent zero-arg lookups off `runBlocking`.
 */
private fun StringDesc.resolveIn(resolved: RaftResolvedResourceEnvironment): String {
    val environment = resolved.environment
    return when (this) {
        is RawStringDesc -> string
        is ResourceStringDesc -> {
            val cacheKey = RaftResolvedStringCacheKey(resolved.language, resolved.region, stringRes)
            RaftResolvedStringCache.get(cacheKey)
                ?: runBlocking { getString(environment, stringRes) }.also { RaftResolvedStringCache.put(cacheKey, it) }
        }
        is ResourceFormattedStringDesc -> {
            // kmmresource resolved StringDesc arguments before formatting; keep that.
            val resolvedArgs = args.map { arg -> (arg as? StringDesc)?.resolveIn(resolved) ?: arg }
            raftLanguagePluralForm(stringRes.key, resolvedArgs, resolved.language)
                ?: runBlocking { getString(environment, stringRes, *resolvedArgs.toTypedArray()) }
        }
    }
}

/**
 * The count string [resourceKey] in the app language's own plural form (few / many for Russian), or
 * null to show the catalog string the caller chose. See RaftPlurals.kt.
 */
private fun raftLanguagePluralForm(resourceKey: String, args: List<Any>, language: String): String? {
    val forms = raftPluralFormsFor(language)
    return if (forms.isEmpty()) null else raftPluralFormFor(forms, language, resourceKey, args)
}

/**
 * `files/plurals/ru.json`, read once. A thread-safe lazy, not a shared map: strings resolve on
 * background threads too.
 */
private val raftRussianPluralForms: RaftPluralForms by lazy {
    runCatching { parseRaftPluralForms(runBlocking { Res.readBytes("files/plurals/ru.json") }.decodeToString()) }
        .getOrDefault(emptyMap())
}

/** The language's plural forms; empty when the language has none (only ru has a file). */
private fun raftPluralFormsFor(language: String): RaftPluralForms =
    if (language == "ru") raftRussianPluralForms else emptyMap()

/** Language and region of the catalog [localeType] selects ("" = no region). */
@RaftCoreInternalApi data class RaftResourceLocale(val language: String, val region: String) {
    /** BCP 47 tag of the catalog (`en`, `es`, `pt-BR`, `ru`, `ko`, `ja`, `zh-CN`, `zh-TW`), the web's `displayLocaleTag`. */
    val languageTag: String get() = if (region.isEmpty()) language else "$language-$region"
}

/** The UI language the app's strings currently resolve in, as a BCP 47 tag (`en`, `zh-CN`, `ja`). */
@RaftCoreInternalApi fun raftCurrentUiLocaleTag(): String =
    raftResourceLocale(StringDesc.localeType, raftSystemPreferredLanguageTagsOrEmpty()).languageTag.ifBlank { "en" }

/**
 * [raftCurrentUiLocaleTag] in composition: the language the strings here resolve in, read from the
 * shared lookup environment, so a language switch recomposes the caller as it does [stringResource].
 */
@Composable
@RaftCoreInternalApi fun rememberRaftUiLocaleTag(): String {
    val environment = rememberRaftResourceEnvironment()
    return remember(environment) { RaftResourceLocale(environment.language, environment.region).languageTag.ifBlank { "en" } }
}

/**
 * The system's preferred languages, most preferred first, as BCP 47 tags (Android: the locale
 * list; iOS: `NSLocale.preferredLanguages`).
 */
@RaftCoreInternalApi expect fun raftSystemPreferredLanguageTags(): List<String>

/** [raftSystemPreferredLanguageTags], or empty where the platform cannot tell (unit-test stubs). */
@RaftCoreInternalApi fun raftSystemPreferredLanguageTagsOrEmpty(): List<String> =
    runCatching { raftSystemPreferredLanguageTags() }.getOrDefault(emptyList())

/**
 * The catalog the system language selects: the one source for UI strings, the UI text locale
 * (Han glyph forms) and the display language tag, so the three always agree.
 */
@RaftCoreInternalApi fun raftSystemResourceLocale(
    systemLanguageTags: List<String> = raftSystemPreferredLanguageTagsOrEmpty(),
): RaftResourceLocale = raftResourceLocale(StringDesc.LocaleType.System, systemLanguageTags)

/**
 * Catalog selection, matching what the platform resource lookups did for kmmresource: an
 * explicit tag picks its catalog; for the system language the first preferred language with a
 * shipped catalog wins (English, Spanish, Brazilian Portuguese for any Portuguese, Russian, Korean,
 * Japanese; Traditional Chinese for a Hant script or a TW/HK/MO region without a script, else
 * Simplified Chinese), the same matching as the web's `matchDisplayLocale`. Unsupported
 * languages are skipped; with no match the English base catalog applies.
 */
@RaftCoreInternalApi fun raftResourceLocale(
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
        "es" -> RaftResourceLocale("es", "")
        "ru" -> RaftResourceLocale("ru", "")
        "ko" -> RaftResourceLocale("ko", "")
        // One Portuguese catalog, Brazilian, for every region (pt-PT too), as on the web.
        "pt" -> RaftResourceLocale("pt", "BR")
        "zh" -> {
            val traditional =
                script.equals("Hant", ignoreCase = true) || (script.isEmpty() && region in setOf("TW", "HK", "MO"))
            if (traditional) RaftResourceLocale("zh", "TW") else RaftResourceLocale("zh", "CN")
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
private fun raftResolvedResourceEnvironment(
    localeType: StringDesc.LocaleType,
    systemLanguageTags: List<String>,
): RaftResolvedResourceEnvironment {
    val locale = raftResourceLocale(localeType, systemLanguageTags)
    // Strings carry no theme or density qualifiers; these two only satisfy the constructor.
    return RaftResolvedResourceEnvironment(
        environment =
            ResourceEnvironment(
                LanguageQualifier(locale.language),
                ScriptQualifier(""),
                RegionQualifier(locale.region),
                ThemeQualifier.LIGHT,
                DensityQualifier.MDPI,
            ),
        language = locale.language,
        region = locale.region,
    )
}
