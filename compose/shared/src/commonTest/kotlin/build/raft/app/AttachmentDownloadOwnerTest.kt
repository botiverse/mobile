package build.raft.app

import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentDownloadOwnerTest {
    @Test
    fun principalKeyUsesStructuredFullStrengthIdentity() {
        val a = attachmentPrincipalKey("env", "Aa", "server")
        val b = attachmentPrincipalKey("env", "BB", "server")
        val delimiterA = attachmentPrincipalKey("env", "a|b", "c")
        val delimiterB = attachmentPrincipalKey("env", "a", "b|c")
        val unicode = attachmentPrincipalKey("环境", "用户", "服务器")

        assertEquals(64, a.value.length)
        assertNotEquals(a, b)
        assertNotEquals(delimiterA, delimiterB)
        assertEquals(64, unicode.value.length)
    }

    @Test
    fun accountTransitionRetiresAllScopesAndOldLeaseNeverRevives() {
        val registry = AttachmentAuthScopeRegistry()
        val first = registry.bind(scope("shell-a"), session("account-a"), "server-a")!!
        val sibling = registry.bind(scope("shell-b"), session("account-a"), "server-b")!!

        val replacement = registry.bind(scope("shell-a"), session("account-b"), "server-a")!!

        assertFalse(registry.allows(first))
        assertFalse(registry.allows(sibling))
        assertTrue(registry.isTombstonedForTesting(first.scopeId))
        assertTrue(registry.isTombstonedForTesting(sibling.scopeId))
        assertTrue(registry.allows(replacement))
        assertNotEquals(first.authEpoch, replacement.authEpoch)
    }

    @Test
    fun serverSwitchRetiresOnlyTheSwitchingShell() {
        val registry = AttachmentAuthScopeRegistry()
        val first = registry.bind(scope("shell-a"), session("account-a"), "server-a")!!
        val sibling = registry.bind(scope("shell-b"), session("account-a"), "server-b")!!
        val switched = registry.bind(scope("shell-a"), session("account-a"), "server-c")!!

        assertFalse(registry.allows(first))
        assertTrue(registry.allows(sibling))
        assertTrue(registry.allows(switched))
        assertNotEquals(first.scopeId, switched.scopeId)
        assertTrue(switched.generation > first.generation)
    }

    @Test
    fun retiredScopeCannotCommitOrControlSuccessor() {
        val registry = AttachmentAuthScopeRegistry()
        val first = registry.bind(scope("shell-a"), session("account-a"), "server-a")!!
        val token = AttachmentOperationToken(first, "operation-a", "download", "attempt-1", 1L)
        registry.retireShell("shell-a")
        val replacement = registry.bind(scope("shell-a"), session("account-a"), "server-a")!!

        assertFalse(registry.commitAllowed(token, "operation-a"))
        assertFalse(registry.allows(first))
        assertTrue(registry.allows(replacement))
        assertNull(registry.current("missing"))
    }

    private fun scope(id: String) = RaftShellStoreScope.create(id)

    private fun session(accountId: String) =
        StoredAuthSession(
            user = StoredAuthUser(id = accountId, email = "$accountId@example.test", name = accountId),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-a",
            servers = listOf(StoredAuthServer(id = "server-a", name = "A", slug = "a"))
        )
}
