package build.raft.app

import build.raft.app.members.memberRemovalImpactJoin
import build.raft.app.members.membersAgentAppAbilities
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.resources.member_app_can_summary
import build.raft.app.resources.member_removal_impact_list_end
import build.raft.app.resources.member_removal_impact_list_more
import build.raft.app.resources.member_removal_impact_list_two
import build.raft.app.resources.resolve
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.StringResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Apps row's sentence reads as the web's (botiverse/slock #14769, web catalogs at
 * ae2c99ce1aac7b495e8c0646aa67d41862a90572): the App is the subject, "{app} can {abilities}", the
 * abilities joined as `Intl.ListFormat` conjunction, assembled as `membersAgentAppCanSummary` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentAppSummaryLocaleTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private fun text(resource: StringResource, vararg args: String): String =
        if (args.isEmpty()) resource.desc().resolve() else resource.format(*args).resolve()

    private fun summary(tag: String, app: String, scopes: List<String>): String {
        StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
        val words = membersAgentAppAbilities(scopes).map { item -> item.ability?.let { text(it.short) } ?: item.rawScope.orEmpty() }
        val joined =
            memberRemovalImpactJoin(
                words,
                { a, b -> text(Res.string.member_removal_impact_list_two, a, b) },
                { a, b -> text(Res.string.member_removal_impact_list_more, a, b) },
                { a, b -> text(Res.string.member_removal_impact_list_end, a, b) },
            )
        return text(Res.string.member_app_can_summary, app, joined)
    }

    @Test
    fun theAppIsTheSubjectInEveryLanguage() {
        val signInAndNotify = listOf("openid", "profile", "agent:notification:write")
        mapOf(
            "en" to "Stamp can sign in as this agent and send notifications",
            "zh-CN" to "Stamp 可以用这个 Agent 的身份登录和发送通知",
            "ja" to "Stamp ができること：このエージェントとしてログイン、通知の送信",
        ).forEach { (tag, expected) ->
            assertEquals(expected, summary(tag, "Stamp", signInAndNotify), "locale $tag")
        }
    }

    @Test
    fun threeAbilitiesAndAnUnknownScopeReadAsOneList() {
        assertEquals(
            "Stamp can sign in as this agent, see this agent's email address, ask this agent to act, and linear:read",
            summary("en", "Stamp", listOf("linear:read", "agent:action_request:write", "email", "openid")),
        )
    }
}
