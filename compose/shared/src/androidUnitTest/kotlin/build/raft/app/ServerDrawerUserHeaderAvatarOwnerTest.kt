package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ServerDrawerUserHeaderAvatarOwnerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sameUserAndAvatarServerSwitchRebindsDrawerAvatarCacheOwner() = runTest {
        val user =
            StoredAuthUser(
                id = "user-1",
                email = "same-user@example.com",
                displayName = "Same User",
                avatarUrl = "https://cdn.example/avatar.png"
            )
        val servers =
            listOf(
                StoredAuthServer(id = "server-a", name = "Server A"),
                StoredAuthServer(id = "server-b", name = "Server B")
            )
        var session by
            mutableStateOf(
                StoredAuthSession(
                    user = user,
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-a",
                    servers = servers
                )
            )
        val observedCacheServerIds = mutableListOf<String?>()
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(ServerDrawerUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }

        try {
            composition.setContent {
                val userHeader = rememberRaftServerDrawerUserHeader(session)
                SideEffect { observedCacheServerIds += userHeader.serverId }
            }
            advanceUntilIdle()

            session = session.copy(selectedServerId = "server-b")
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(System.nanoTime())
            advanceUntilIdle()
        } finally {
            composition.dispose()
            recomposer.close()
            withTimeout(5_000L) { recomposerJob.join() }
        }

        assertEquals("server-a", observedCacheServerIds.first())
        assertEquals("server-b", observedCacheServerIds.last())
    }
}

private class ServerDrawerUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
