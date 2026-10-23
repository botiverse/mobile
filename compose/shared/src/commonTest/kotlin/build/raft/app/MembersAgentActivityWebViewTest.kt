package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembersAgentActivityWebViewTest {
    private val base = "https://raft-app-staging.botiverse.dev"
    private val document = "$base/s/lagoon-push-test/agent/agent-1/activity?embed=raft-settings-v1&shell=host&lang=en"

    @Test
    fun urlIsTheHostShellActivityPageOnTheEnvironmentWebOrigin() {
        assertEquals(document, agentActivityWebViewUrl("$base/", "lagoon-push-test", "agent-1", "en"))
        assertEquals(
            "https://app.raft.build/s/team/agent/a2/activity?embed=raft-settings-v1&shell=host&lang=zh-CN",
            agentActivityWebViewUrl("https://app.raft.build", "team", "a2", "zh-CN"),
        )
    }

    @Test
    fun theActivityDocumentAndTheAuthBootstrapStay() {
        assertEquals(AgentActivityWebNavigation.Stay, agentActivityWebNavigation(document, document, base))
        assertEquals(AgentActivityWebNavigation.Stay, agentActivityWebNavigation(document, "$document#top", base))
        assertEquals(AgentActivityWebNavigation.Stay, agentActivityWebNavigation(document, "", base))
        assertEquals(AgentActivityWebNavigation.Stay, agentActivityWebNavigation(document, "about:blank", base))
        assertEquals(
            AgentActivityWebNavigation.Stay,
            agentActivityWebNavigation(document, "$base/__raft_mobile_bootstrap__", base),
        )
    }

    @Test
    fun aMessageLinkOnTheSameEnvironmentOpensNatively() {
        val permalink = "$base/s/lagoon-push-test/channel/c1?msg=m1"
        assertEquals(
            AgentActivityWebNavigation.OpenMessage(permalink),
            agentActivityWebNavigation(document, permalink, base),
        )
        val threadReply = "$base/s/lagoon-push-test/dm/d1?msg=m2&thread=d1:p1"
        assertEquals(
            AgentActivityWebNavigation.OpenMessage(threadReply),
            agentActivityWebNavigation(document, threadReply, base),
        )
    }

    @Test
    fun otherInPageRoutesHaveNoNativeTarget() {
        listOf(
            "$base/s/lagoon-push-test/channel/c1",
            "$base/s/lagoon-push-test/computer/m1",
            "$base/s/lagoon-push-test/agent/agent-1",
            "$base/s/lagoon-push-test/agent/agent-1/activity?embed=raft-settings-v1",
        ).forEach { url ->
            assertEquals(AgentActivityWebNavigation.Ignore, agentActivityWebNavigation(document, url, base), url)
        }
    }

    @Test
    fun offSiteLinksGoToTheLinkOpenerAndOtherSchemesAreDropped() {
        assertEquals(
            AgentActivityWebNavigation.OpenExternal("https://github.com/botiverse/slock/pull/1"),
            agentActivityWebNavigation(document, "https://github.com/botiverse/slock/pull/1", base),
        )
        assertEquals(AgentActivityWebNavigation.Ignore, agentActivityWebNavigation(document, "intent://x", base))
    }

    @Test
    fun injectedScriptCarriesAuthThemeAndNoSelectCss() {
        val dark = agentActivityWebViewInjectedJavaScript(session = null, hostBinding = null, dark = true)
        assertTrue(dark.contains("\"slock-theme-preferences-v2\""))
        assertTrue(dark.contains("\\\"mode\\\":\\\"dark\\\""))
        assertTrue(dark.contains("-webkit-touch-callout:none"))
        assertTrue(dark.contains("user-select:none"))
        assertTrue(dark.contains("slock_access_token"))
        val light = agentActivityWebViewInjectedJavaScript(session = null, hostBinding = null, dark = false)
        assertTrue(light.contains("\\\"mode\\\":\\\"light\\\""))
    }
}
