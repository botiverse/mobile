package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.ProPricing
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.resources.resolve
import build.raft.app.settings.settingsBillingCountUsageDesc
import build.raft.app.settings.settingsBillingSeatsBreakdown
import build.raft.app.settings.termDesc
import build.raft.app.settings.valueDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS Plan & billing's seats breakdown says what the web's SeatsBreakdown says (botiverse/slock
 * 16f5f8bc40, #15505; web catalogs `planBilling.usage.peopleCount` / `agentsCount` / `seatsValue` /
 * `agentSeatsValue` at that commit): a label then its seats, one row per kind, the rule beside the
 * agents' seats, the short rule in zh and ja.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBillingSeatsBreakdownLocaleTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    /** Each row as (label, seats), resolved in [tag]. */
    private fun breakdown(tag: String, humans: Int, agents: Int, pricing: ProPricing = ProPricing.SeatV1): List<Pair<String, String>> {
        StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
        return settingsBillingSeatsBreakdown(humans, agents, pricing).map { it.termDesc().resolve() to it.valueDesc().resolve() }
    }

    @Test
    fun personV2OmitsAgentsRowInEveryLocale() {
        assertEquals(listOf("4 people" to "4 seats"), breakdown("en", 4, 15, ProPricing.PersonV2))
        assertEquals(listOf("3 человека" to "3 места"), breakdown("ru", 3, 12, ProPricing.PersonV2))
    }

    @Test
    fun peopleThenAgentsWithTheirSeats() {
        mapOf(
            // Whole numbers, and a fraction of a seat for agents (web proCheckoutSeatsInUse test).
            (4 to 15) to listOf("4 people" to "4 seats", "15 agents" to "1.5 seats (1 per 10 agents)"),
            (4 to 3) to listOf("4 people" to "4 seats", "3 agents" to "0.3 seats (1 per 10 agents)"),
            // Ten agents are one seat, said in the singular, with the rule beside it.
            (2 to 10) to listOf("2 people" to "2 seats", "10 agents" to "1 seat (1 per 10 agents)"),
            // No agents row when there are none.
            (6 to 0) to listOf("6 people" to "6 seats"),
            (1 to 0) to listOf("1 person" to "1 seat"),
            (1 to 1) to listOf("1 person" to "1 seat", "1 agent" to "0.1 seats (1 per 10 agents)"),
        ).forEach { (usage, rows) ->
            assertEquals(rows, breakdown("en", usage.first, usage.second), "$usage")
        }
    }

    /** Russian counts and whole seats take one / few / many (files/plurals/ru.json); a fraction of a seat keeps `other`. */
    @Test
    fun russianSeatsTakeTheirOwnForms() {
        mapOf(
            (1 to 10) to listOf("1 человек" to "1 место", "10 агентов" to "1 место (1 на 10 агентов)"),
            (3 to 12) to listOf("3 человека" to "3 места", "12 агентов" to "1.2 места (1 на 10 агентов)"),
            (5 to 50) to listOf("5 человек" to "5 мест", "50 агентов" to "5 мест (1 на 10 агентов)"),
            (21 to 210) to listOf("21 человек" to "21 место", "210 агентов" to "21 место (1 на 10 агентов)"),
            (22 to 1) to listOf("22 человека" to "22 места", "1 агент" to "0.1 места (1 на 10 агентов)"),
        ).forEach { (usage, rows) ->
            assertEquals(rows, breakdown("ru", usage.first, usage.second), "$usage")
        }
    }

    @Test
    fun appSumoUnlimitedUsageLocalizedAcrossAllEightLocales() {
        val expectedByLocale =
            mapOf(
                AppLocale.English to "11 used · Unlimited",
                AppLocale.Spanish to "En uso: 11 · Sin límite",
                AppLocale.Japanese to "11 件使用中（上限なし）",
                AppLocale.Korean to "11개 사용 중 · 무제한",
                AppLocale.BrazilianPortuguese to "Em uso: 11 · Sem limite",
                AppLocale.Russian to "Используется: 11 · Без ограничений",
                AppLocale.SimplifiedChinese to "已用 11 个，无上限",
                AppLocale.TraditionalChinese to "已使用 11 個，無上限",
            )
        expectedByLocale.forEach { (locale, expected) ->
            StringDesc.localeType = locale.stringDescLocaleType
            assertEquals(expected, settingsBillingCountUsageDesc(used = 11, limit = -1).resolve(), locale.name)
        }
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        assertEquals("1 of 1 used", settingsBillingCountUsageDesc(used = 1, limit = 1).resolve())
    }
}
