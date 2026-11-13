package build.raft.app

import build.raft.app.data.AppLinkConsentAnswer
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.FakeAppLinkPreviewApi
import build.raft.app.data.MessageCardActingPage
import build.raft.app.data.SharedAppLinkPreviewStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The question a card message's acting button asks before the viewer agreed to its App (RFC 086
 * §4.2, `consent: "needed"`): Not now only closes it, sends nothing (never a decline) and the next
 * press asks again; Allow sends the consent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMessageCardConsentFlowTest {
    private val prompt = AppLinkResult.Consent(MessageCardActingPage, AppLinkPromptApp("client-vault", "Vault", null))

    @Test
    fun notNowSendsNothingAndTheNextPressAsksAgain() = runTest(StandardTestDispatcher()) {
        val api = FakeAppLinkPreviewApi()
        val flow = ThreadMessageCardConsentFlow(SharedAppLinkPreviewStore(api = api, scope = backgroundScope), "user-alice:server-1", "server-1")
        flow.ask(prompt)
        flow.notNow()
        assertNull(flow.asking)
        assertEquals(emptyList(), api.answers, "Not now is no decline: nothing reaches the server")
        flow.ask(prompt)
        assertEquals(prompt, flow.asking)
        assertEquals(AppLinkConsentAnswer.Allowed, flow.allow())
        assertEquals(listOf("allow:client-vault"), api.answers)
        assertNull(flow.asking)
    }
}
