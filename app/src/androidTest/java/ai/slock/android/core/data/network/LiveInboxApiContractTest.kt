package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.ServerId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInboxApiContractTest {
    @Test
    fun liveInboxApiMatchesRepositoryMapping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedAuthSessionStore(context)
        val session = store.readSession()
        assumeTrue("No signed-in app session available for live inbox API check.", session != null)

        val serverId = (session?.currentServerId ?: session?.servers?.firstOrNull()?.id)?.let(::ServerId)
        assumeTrue("Signed-in session has no selected server for live inbox API check.", serverId != null)

        val repository = RealInboxRepository(
            store = store,
            api = RealSlockApiFactory.create(store)
        )

        val result = repository.loadInitial(
            serverId = requireNotNull(serverId),
            filter = InboxFilter.All,
            limit = 30
        )

        assertTrue(
            "Live inbox API should return content, empty, or no-permission instead of a mapping/network error: $result",
            result is DataResult.Content || result is DataResult.Empty || result is DataResult.NoPermission
        )
        if (result is DataResult.Content) {
            val threadItems = result.data.items.filter { it.kind == InboxItemKind.Thread }
            threadItems.forEach { item ->
                assertTrue(
                    "Thread inbox item should carry parent or latest activity preview: ${item.key}",
                    !item.parentMessagePreview.isNullOrBlank() ||
                        !item.latestActivityPreview.isNullOrBlank()
                )
            }
        }
    }
}
