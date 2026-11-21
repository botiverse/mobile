package build.raft.app

import build.raft.app.resources.StringDesc
import build.raft.app.resources.resolve
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
    private fun breakdown(tag: String, humans: Int, agents: Int): List<Pair<String, String>> {
        StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
        return settingsBillingSeatsBreakdown(humans, agents).map { it.termDesc().resolve() to it.valueDesc().resolve() }
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

    /**
     * The web's words in every language. Its numbers come from ICU (`formatNumber`, `#`): es, pt-BR
     * and ru write "1,5" there; this page writes "1.5" in every language, as its seat meter does
     * ([settingsBillingFormatSeats]).
     */
    @Test
    fun everyLanguageSaysItAsTheWeb() {
        mapOf(
            "zh-CN" to listOf("4 位成员" to "4 个席位", "15 个 Agent" to "1.5 个席位（每 10 个 1 席）"),
            "zh-TW" to listOf("4 位成員" to "4 個席位", "15 個 Agent" to "1.5 個席位（每 10 個 1 席）"),
            "ja" to listOf("4 人" to "4 席", "エージェント 15 個" to "1.5 席（10 個で 1 席）"),
            "ko" to listOf("사람 4명" to "시트 4개", "에이전트 15개" to "시트 1.5개 (에이전트 10개당 1개)"),
            "es" to listOf("4 personas" to "4 puestos", "15 agentes" to "1.5 puestos (1 cada 10 agentes)"),
            "pt-BR" to listOf("4 pessoas" to "4 licenças", "15 agentes" to "1.5 licenças (1 a cada 10 agentes)"),
            "ru" to listOf("4 человека" to "4 места", "15 агентов" to "1.5 места (1 на 10 агентов)"),
        ).forEach { (tag, rows) ->
            assertEquals(rows, breakdown(tag, 4, 15), "locale $tag")
        }
        // One person, one seat of agents: zh keeps one form for every count.
        assertEquals(listOf("1 位成员" to "1 个席位", "10 个 Agent" to "1 个席位（每 10 个 1 席）"), breakdown("zh-CN", 1, 10))
        assertEquals(listOf("1 人" to "1 席", "エージェント 10 個" to "1 席（10 個で 1 席）"), breakdown("ja", 1, 10))
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
}
