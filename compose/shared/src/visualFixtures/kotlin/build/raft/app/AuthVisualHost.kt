package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser

@Composable
internal fun AuthChooseServerVisualContent() {
    val session = remember { authChooseServerVisualSession() }
    val store = remember(session) { InMemoryRaftAuthSessionStore(session) }
    val authDataSource = remember(store, session) {
        SharedAuthDataSource(
            api = AuthChooseServerVisualApi(session.user, session.servers),
            sessionStore = store
        )
    }
    AppShellServerGateContent(
        session = session,
        authDataSource = authDataSource,
        onReady = {},
        onSignOut = {}
    )
}

@Composable
internal fun AuthEmailVerificationVisualContent() {
    val session =
        remember {
            authChooseServerVisualSession().let { base ->
                base.copy(user = base.user.copy(emailVerified = false))
            }
        }
    val store = remember(session) { InMemoryRaftAuthSessionStore(session) }
    val authDataSource =
        remember(store, session) {
            SharedAuthDataSource(
                api = AuthChooseServerVisualApi(session.user, session.servers),
                sessionStore = store
            )
        }
    AppShellEmailVerificationGateContent(
        session = session,
        authDataSource = authDataSource,
        onRefresh = {},
        onSignOut = {}
    )
}

private fun authChooseServerVisualSession(): StoredAuthSession =
    StoredAuthSession(
        user =
            StoredAuthUser(
                id = "user_visual",
                email = "artin@raft.build",
                displayName = "Artin"
            ),
        accessToken = "visual-access-token",
        refreshToken = "visual-refresh-token",
        selectedServerId = null,
        selectedServerSlug = null,
        servers =
            listOf(
                StoredAuthServer(
                    id = "srv_design",
                    name = "Slock Design",
                    slug = "slock-design",
                    role = "owner"
                ),
                StoredAuthServer(
                    id = "srv_android",
                    name = "Android Artifacts",
                    slug = "android-artifacts",
                    role = "admin"
                ),
                StoredAuthServer(
                    id = "srv_feedback",
                    name = "Feedback",
                    slug = "feedback",
                    role = "member"
                )
            )
    )
