@file:OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)

package build.raft.app.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import build.raft.app.KmmResourceLocaleRuntime
import kotlin.concurrent.Volatile
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
        /** Process-wide app language for string lookups. */
        @Volatile
        var localeType: LocaleType = LocaleType.System
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
fun StringDesc.resolve(): String = resolveIn(raftResourceEnvironment(StringDesc.localeType, Locale.current))

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
 * The lookup environment for the current [StringDesc.localeType]. Reading
 * [KmmResourceLocaleRuntime.locale] subscribes the caller to app-language changes (the shell seed
 * updates [StringDesc.localeType] in the same recomposition, before its children).
 */
@Composable
private fun rememberRaftResourceEnvironment(): ResourceEnvironment {
    val runtimeLocale by KmmResourceLocaleRuntime.locale.collectAsState()
    val localeType = StringDesc.localeType
    val systemLocale = Locale.current
    return remember(runtimeLocale, localeType, systemLocale) { raftResourceEnvironment(localeType, systemLocale) }
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
 * Catalog selection, matching what the platform resource lookups did for kmmresource:
 * an explicit tag picks its catalog; the system language picks Japanese for `ja`, Simplified
 * Chinese for Chinese except Traditional (Hant script, or TW/HK/MO without a script), and the
 * English base catalog otherwise.
 */
internal fun raftResourceLocale(
    localeType: StringDesc.LocaleType,
    systemLanguage: String,
    systemScript: String,
    systemRegion: String,
): RaftResourceLocale =
    when (localeType) {
        is StringDesc.LocaleType.Custom -> {
            val parts = localeType.localeTag.replace('_', '-').split('-').filter { it.isNotEmpty() }
            RaftResourceLocale(
                language = parts.firstOrNull()?.lowercase().orEmpty(),
                region = parts.drop(1).firstOrNull { it.length == 2 }?.uppercase().orEmpty(),
            )
        }
        StringDesc.LocaleType.System ->
            when (systemLanguage.lowercase()) {
                "ja" -> RaftResourceLocale("ja", "")
                "zh" -> {
                    val traditional =
                        systemScript.equals("Hant", ignoreCase = true) ||
                            (systemScript.isEmpty() && systemRegion.uppercase() in setOf("TW", "HK", "MO"))
                    if (traditional) RaftResourceLocale("en", "") else RaftResourceLocale("zh", "CN")
                }
                else -> RaftResourceLocale("en", "")
            }
    }

/**
 * Compose resources picks a catalog from a [ResourceEnvironment] but only builds one for the
 * system locale (`getSystemResourceEnvironment`); its constructor is library-internal. The app
 * language is an in-app setting that must not change the process locale (that would also change
 * date and number formatting and CJK glyph selection), so the environment is built directly.
 * Covered by RaftStringDescResolutionTest; revisit when Compose resources gains a public
 * locale override.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private fun raftResourceEnvironment(localeType: StringDesc.LocaleType, systemLocale: Locale): ResourceEnvironment {
    val locale = raftResourceLocale(localeType, systemLocale.language, systemLocale.script, systemLocale.region)
    // Strings carry no theme or density qualifiers; these two only satisfy the constructor.
    return ResourceEnvironment(
        LanguageQualifier(locale.language),
        ScriptQualifier(""),
        RegionQualifier(locale.region),
        ThemeQualifier.LIGHT,
        DensityQualifier.MDPI,
    )
}
