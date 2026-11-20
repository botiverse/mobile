package build.raft.app.core.data.auth

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Process
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** The real provider's call(): the binder caller's uid decides, whatever the request says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppIdentityReceiptProviderCallTest {
    private val provider =
        Robolectric.buildContentProvider(AppIdentityReceiptProvider::class.java)
            .create(ProviderInfo().apply { authority = "build.raft.app.identity-receipt" })
            .get()

    private fun validExtras() =
        Bundle().apply {
            putString("attempt_id", "pla_" + "0a".repeat(16))
            putString("nonce", "ab".repeat(32))
            putString("request_id", "cd".repeat(16))
        }

    @After
    fun resetUid() {
        ShadowBinder.reset()
    }

    @Test
    fun anyUidButShellOrRootGetsOnlyARefusalCode() {
        // The provider ships enabled, so this is the gate: the app's own uid, system, other system
        // uids, ordinary apps (10xxx), an isolated process, a secondary user's app, an invalid uid.
        val uids = listOf(Process.myUid(), 1000, 1001, 1002, 1999, 2001, 9999, 10_000, 10_123, 19_999, 99_000, 1_010_123, -1)
        for (uid in uids) {
            ShadowBinder.setCallingUid(uid)
            // Even a request claiming to come from the shell is refused: arguments never authorize.
            val extras = validExtras().apply { putString("caller", "shell") }
            for (request in listOf(validExtras(), extras)) {
                val result = provider.call("receipt_v1", null, request)
                assertEquals("uid $uid", setOf(AppIdentityReceiptProvider.RESULT_ERROR), result.keySet())
                assertEquals("uid $uid", "caller_not_allowed", result.getString(AppIdentityReceiptProvider.RESULT_ERROR))
                assertNull("uid $uid", result.getString(AppIdentityReceiptProvider.RESULT_RECEIPT))
            }
        }
        // Refusals did not use up any answer: the shell is still answered past the uid gate.
        ShadowBinder.setCallingUid(AppIdentityReceiptResponder.SHELL_UID)
        assertNotEquals("caller_not_allowed", provider.call("receipt_v1", null, validExtras()).getString(AppIdentityReceiptProvider.RESULT_ERROR))
    }

    @Test
    fun creatingTheProviderAtProcessStartDoesNoWork() {
        // Enabled providers are created before Application.onCreate: no context, nothing initialized.
        val bare = AppIdentityReceiptProvider()
        assertTrue(bare.onCreate())
        assertNull("onCreate must not need a context", bare.context)
    }

    @Test
    fun shellUidIsAuthorizedAndOtherEntryPointsReturnNothing() {
        ShadowBinder.setCallingUid(AppIdentityReceiptResponder.SHELL_UID)
        val result = provider.call("receipt_v1", null, validExtras())
        // Past the uid gate: either a receipt or a non-authorization refusal (no session loaded here).
        assertNotEquals("caller_not_allowed", result.getString(AppIdentityReceiptProvider.RESULT_ERROR))
        assertEquals("bad_request", provider.call("receipt_v1", null, Bundle().apply { putAll(validExtras()); putInt("x", 1) }).getString("error"))

        val uri = android.net.Uri.parse("content://build.raft.app.identity-receipt/")
        assertNull(provider.query(uri, null, null, null, null))
        assertNull(provider.getType(uri))
        assertNull(provider.insert(uri, null))
        assertEquals(0, provider.update(uri, null, null, null))
        assertEquals(0, provider.delete(uri, null, null))
        assertFalse(result.isEmpty)
    }
}
