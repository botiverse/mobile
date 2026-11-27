package build.raft.app

import build.raft.app.data.AgentOperatorEvent
import build.raft.app.data.AgentOperatorEventActor
import build.raft.app.data.AgentOperatorEventKind
import build.raft.app.members.membersActivityOverviewOperatorEventText
import build.raft.app.members.membersActivityOverviewOperatorEventsSummary
import build.raft.app.resources.StringDesc
import build.raft.app.resources.resolve
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The overview's operator event lines read as the web's (`operatorEventText`, botiverse/slock
 * #13937; web catalogs `activity.overview.operator.*` at 18d389311f299d559e237c3f8875d65491a227f3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentActivityOperatorEventsLocaleTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val artin = AgentOperatorEventActor.User("Artin")

    private fun event(
        kind: AgentOperatorEventKind,
        actor: AgentOperatorEventActor = artin,
        from: String? = null,
        to: String? = null,
        beforeModel: String? = null,
        afterModel: String? = null,
        beforeRuntime: String? = null,
        afterRuntime: String? = null,
    ) = AgentOperatorEvent(
        id = kind.wireValue,
        atEpochMillis = 0,
        kind = kind,
        actor = actor,
        fromComputer = from,
        toComputer = to,
        beforeModel = beforeModel,
        afterModel = afterModel,
        beforeRuntime = beforeRuntime,
        afterRuntime = afterRuntime,
    )

    private fun line(tag: String, event: AgentOperatorEvent): String {
        StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
        return membersActivityOverviewOperatorEventText(event).resolve().trim()
    }

    @Test
    fun everyKindReadsAsTheWebInEnglish() {
        mapOf(
            event(AgentOperatorEventKind.Start) to "Artin started the agent",
            event(AgentOperatorEventKind.Stop) to "Artin stopped the agent",
            event(AgentOperatorEventKind.Restart) to "Artin restarted the agent",
            event(AgentOperatorEventKind.ResetSession) to "Artin reset the session and restarted",
            event(AgentOperatorEventKind.ResetFull) to "Artin reset the agent, workspace included",
            event(AgentOperatorEventKind.TurnStopped) to "Artin stopped the current turn",
            event(AgentOperatorEventKind.MigrationStarted, from = "laptop", to = "hetzner-1") to
                "Artin started moving the agent from laptop to hetzner-1",
            // One Computer no longer on the server: the line without names.
            event(AgentOperatorEventKind.MigrationStarted, to = "hetzner-1") to "Artin started moving the agent to another computer",
            event(AgentOperatorEventKind.MigrationCancelled) to "Artin cancelled the move",
            event(AgentOperatorEventKind.ConfigChanged, beforeModel = "gpt-5", afterModel = "gpt-5.6") to
                "Artin changed the model: gpt-5 → gpt-5.6",
            event(AgentOperatorEventKind.ConfigChanged, beforeRuntime = "codex", afterRuntime = "claude") to
                "Artin changed the runtime: codex → claude",
            event(AgentOperatorEventKind.ConfigChanged) to "Artin changed the agent's settings",
            event(AgentOperatorEventKind.SystemRestart, actor = AgentOperatorEventActor.System) to
                "Raft restarted the agent after failed starts",
            event(AgentOperatorEventKind.Stop, actor = AgentOperatorEventActor.User(null)) to "Someone stopped the agent",
            // An agent's own model change names no one (the web's empty actor).
            event(AgentOperatorEventKind.ConfigChanged, actor = AgentOperatorEventActor.System, beforeModel = "a", afterModel = "b") to
                "changed the model: a → b",
        ).forEach { (event, expected) -> assertEquals(expected, line("en", event), "${event.kind} ${event.actor}") }
    }

    @Test
    fun theFoldedLineCountsTheActionsAndQuotesTheLatest() {
        fun summary(tag: String, count: Int, latest: String): String {
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            return membersActivityOverviewOperatorEventsSummary(count, latest).resolve()
        }
        assertEquals("3 actions · latest: Artin stopped the agent", summary("en", 3, "Artin stopped the agent"))
        assertEquals("3 次操作 · 最近：x", summary("zh-CN", 3, "x"))
        assertEquals("3 件の操作 · 最新：x", summary("ja", 3, "x"))
        // Russian's own plural forms (web ICU plural).
        assertEquals("3 действия · последнее: x", summary("ru", 3, "x"))
        assertEquals("5 действий · последнее: x", summary("ru", 5, "x"))
        assertEquals("21 действие · последнее: x", summary("ru", 21, "x"))
    }
}
