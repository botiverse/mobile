package ai.slock.android.shared

import ai.slock.compose.model.slockEpochDayForDate
import ai.slock.compose.model.SlockTimeFormat24Hour
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

internal object SlockKuiklyPages {
    const val LOGIN = "compose_login"
    const val REGISTER = "compose_register"
    const val FORGOT_PASSWORD = "compose_forgot_password"
    const val APP_SHELL = "compose_slock_app_shell"
    const val THREAD = "compose_thread"
    const val THREAD_COMPOSER_VISUAL = "compose_thread_composer_visual"
    const val HOME = "compose_home"
    const val MEMBERS = "compose_members"
    const val SETTINGS_HOME = "compose_settings_home"
    const val SETTINGS_ACCOUNT = "compose_settings_account"
    const val SETTINGS_COMPUTERS = "compose_settings_computers"
    const val SETTINGS_COMPUTER_DETAIL = "compose_settings_computer_detail"
    const val SETTINGS_STORAGE = "compose_settings_storage"
    const val SETTINGS_BILLING = "compose_settings_billing"
    const val ICON_GALLERY = "compose_icon_gallery"
    const val ROUTER = "router"
}

internal data class SlockKuiklyPageSpec(
        val pageName: String,
        val pageData: SlockKuiklyPageData = SlockKuiklyPageData.empty()
)

internal class SlockKuiklyPageData private constructor(
        private val json: JSONObject
) {
    val text: String = json.toString()
    val instanceId: String get() = json.optString("instanceId")
    val payloadBytes: Int get() = json.optString("payload").length

    fun withInstanceId(instanceId: String): SlockKuiklyPageData {
        if (instanceId.isBlank()) {
            return this
        }
        return fromJson(json).put("instanceId", instanceId)
    }

    fun toKuiklyMap(): MutableMap<String, Any> =
            json.toMap().toMutableMap().apply {
                put("appId", SLOCK_KUIKLY_APP_ID)
                putHostTimeContextIfAbsent()
            }

    private fun put(key: String, value: Any): SlockKuiklyPageData =
            fromJson(json).also { it.json.put(key, value) }

    companion object {
        fun empty(): SlockKuiklyPageData = SlockKuiklyPageData(JSONObject())

        fun fromJson(json: JSONObject): SlockKuiklyPageData =
                SlockKuiklyPageData(JSONObject(json.toString()))

        fun fromText(text: String?): SlockKuiklyPageData =
                if (text.isNullOrBlank()) {
                    empty()
                } else {
                    runCatching { SlockKuiklyPageData(JSONObject(text)) }.getOrElse { empty() }
                }
    }
}

internal fun slockKuiklyPageIsOpaque(pageName: String): Boolean =
        pageName == SlockKuiklyPages.THREAD ||
                pageName == SlockKuiklyPages.THREAD_COMPOSER_VISUAL ||
                pageName == SlockKuiklyPages.APP_SHELL

private const val SLOCK_KUIKLY_APP_ID = 1

private fun MutableMap<String, Any>.putHostTimeContextIfAbsent() {
    if (!containsKey("localOffsetMinutes")) {
        val zoneId = ZoneId.systemDefault()
        put("localOffsetMinutes", zoneId.rules.getOffset(java.time.Instant.now()).totalSeconds / 60)
    }
    if (!containsKey("todayEpochDay")) {
        val today = LocalDate.now(ZoneId.systemDefault())
        put("todayEpochDay", slockEpochDayForDate(today.year, today.monthValue, today.dayOfMonth))
    }
    if (!containsKey("nowEpochMillis")) {
        put("nowEpochMillis", java.time.Instant.now().toEpochMilli())
    }
    if (!containsKey("timeFormat")) {
        put("timeFormat", SlockTimeFormat24Hour)
    }
}
